package com.intellibase.server.config;

import com.intellibase.server.common.Constants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLQ_DOC_PARSE = "doc.parse.dlq";
    public static final String DLQ_DOC_EMBED = "doc.embed.dlq";

    /**
     * MQ 消息序列化：使用 JSON 替代默认的 JDK 序列化
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
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
