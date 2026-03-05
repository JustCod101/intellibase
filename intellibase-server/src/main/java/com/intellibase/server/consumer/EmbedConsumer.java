package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 向量化消费者
 * 监听 doc.embed.queue，批量调用 Embedding API 并写入 pgvector
 */
@Slf4j
@Component
public class EmbedConsumer {

    @RabbitListener(queues = Constants.QUEUE_DOC_EMBED, concurrency = "2-3")
    public void handleEmbed(String message) {
        // TODO: 1. 批量调用 Embedding API (batch_size=100)
        //       2. 批量写入 document_chunk 表
        //       3. 更新 document 状态为 COMPLETED
        log.info("收到向量化消息: {}", message);
    }

}
