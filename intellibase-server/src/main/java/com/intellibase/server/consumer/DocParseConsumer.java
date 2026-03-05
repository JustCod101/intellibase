package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档解析消费者
 * 监听 doc.parse.queue，完成文档下载、Tika 解析、文本分块，然后发送到 embed 队列
 */
@Slf4j
@Component
public class DocParseConsumer {

    @RabbitListener(queues = Constants.QUEUE_DOC_PARSE, concurrency = "3-5")
    public void handleDocParse(String message) {
        // TODO: 1. 从 MinIO 下载文档
        //       2. Tika 解析提取文本
        //       3. TextSplitter 分块
        //       4. 发送到 doc.embed.queue
        log.info("收到文档解析消息: {}", message);
    }

}
