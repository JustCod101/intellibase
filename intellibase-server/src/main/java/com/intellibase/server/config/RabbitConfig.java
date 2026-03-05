package com.intellibase.server.config;

import com.intellibase.server.common.Constants;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    /**
     * 文档解析队列 — 限制并发防止 OOM
     */
    @Bean
    public Queue docParseQueue() {
        return QueueBuilder.durable(Constants.QUEUE_DOC_PARSE)
                .withArgument("x-max-length", 1000)
                .withArgument("x-dead-letter-exchange", "dlx.exchange")
                .build();
    }

    /**
     * 向量化队列 — 控制对 Embedding API 的调用频率
     */
    @Bean
    public Queue docEmbedQueue() {
        return QueueBuilder.durable(Constants.QUEUE_DOC_EMBED)
                .withArgument("x-max-length", 5000)
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
