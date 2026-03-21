package com.intellibase.server.config;

import com.intellibase.server.common.Constants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;

@Configuration
public class RabbitConfig {

    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLQ_DOC_PARSE = "doc.parse.dlq";
    public static final String DLQ_DOC_EMBED = "doc.embed.dlq";

    /** 最大重试次数 */
    private static final int MAX_ATTEMPTS = 3;
    /** 首次重试间隔 (ms) */
    private static final long INITIAL_INTERVAL = 2_000L;
    /** 退避乘数 */
    private static final double MULTIPLIER = 3.0;
    /** 最大重试间隔 (ms) */
    private static final long MAX_INTERVAL = 30_000L;

    /**
     * MQ 消息序列化：使用 JSON 替代默认的 JDK 序列化
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 重试耗尽后的恢复策略：将消息 republish 到死信交换机，附带异常堆栈信息。
     * 相比 RejectAndDontRequeueRecoverer，RepublishMessageRecoverer 会把异常信息
     * 写入消息 header，方便后续排查。
     */
    @Bean
    public RepublishMessageRecoverer republishMessageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE);
    }

    /**
     * 带本地重试的监听容器工厂
     * <p>
     * 重试策略：指数退避 2s → 6s → 18s（最多 3 次），重试耗尽后 republish 到 DLX。
     * 所有 @RabbitListener 默认使用此工厂。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jackson2JsonMessageConverter,
            RepublishMessageRecoverer republishMessageRecoverer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jackson2JsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(MAX_ATTEMPTS)
                        .backOffOptions(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL)
                        .recoverer((MethodInvocationRecoverer<?>) republishMessageRecoverer)
                        .build()
        );
        return factory;
    }

    // ==================== 死信基础设施 ====================

    /**
     * 死信交换机 — 所有队列共享
     */
    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    /**
     * 文档解析死信队列
     */
    @Bean
    public Queue docParseDlq() {
        return QueueBuilder.durable(DLQ_DOC_PARSE).build();
    }

    /**
     * 向量化死信队列
     */
    @Bean
    public Queue docEmbedDlq() {
        return QueueBuilder.durable(DLQ_DOC_EMBED).build();
    }

    @Bean
    public Binding docParseDlqBinding() {
        return BindingBuilder.bind(docParseDlq()).to(dlxExchange()).with(Constants.QUEUE_DOC_PARSE);
    }

    @Bean
    public Binding docEmbedDlqBinding() {
        return BindingBuilder.bind(docEmbedDlq()).to(dlxExchange()).with(Constants.QUEUE_DOC_EMBED);
    }

    // ==================== 业务队列 ====================

    /**
     * 文档解析队列 — 限制并发防止 OOM
     */
    @Bean
    public Queue docParseQueue() {
        return QueueBuilder.durable(Constants.QUEUE_DOC_PARSE)
                .withArgument("x-max-length", 1000)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", Constants.QUEUE_DOC_PARSE)
                .build();
    }

    /**
     * 向量化队列 — 控制对 Embedding API 的调用频率
     */
    @Bean
    public Queue docEmbedQueue() {
        return QueueBuilder.durable(Constants.QUEUE_DOC_EMBED)
                .withArgument("x-max-length", 5000)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", Constants.QUEUE_DOC_EMBED)
                .build();
    }

    /**
     * 推理请求队列 — 高并发场景削峰，支持优先级
     */
    @Bean
    public Queue inferenceQueue() {
        return QueueBuilder.durable(Constants.QUEUE_INFERENCE)
                .withArgument("x-max-priority", 10)
                .build();
    }

}
