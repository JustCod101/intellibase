package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.kb.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 文档解析消费者
 * 监听 doc.parse.queue，完成文档下载、解析、文本分块，然后发送到 embed 队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocParseConsumer {

    private final MinioService minioService;
    private final DocumentMapper documentMapper;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = Constants.QUEUE_DOC_PARSE, concurrency = "3-5")
    public void handleDocParse(DocParseMessage msg) {
        log.info("开始解析文档: docId={}, fileType={}", msg.getDocId(), msg.getFileType());

        try {
            // 1. 更新状态为 PARSING
            documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_PARSING);

            // 2. 从 MinIO 下载文档
            InputStream stream = minioService.downloadFile(msg.getFileKey());

            // 3. 解析文档提取文本（TODO: 接入 Tika 解析引擎）
            String text = parseDocument(stream, msg.getFileType());

            // 4. 文本分块（TODO: 接入 TextSplitter）
            // List<TextChunk> chunks = textSplitter.split(text, msg.getChunkSize(), msg.getChunkOverlap());

            // 5. 更新状态为 EMBEDDING，发送到向量化队列
            documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_EMBEDDING);

            // TODO: 将分块数据批量发送到 doc.embed.queue
            // for (List<TextChunk> batch : Lists.partition(chunks, 100)) {
            //     rabbitTemplate.convertAndSend(Constants.QUEUE_DOC_EMBED, new EmbedBatchMessage(...));
            // }

            log.info("文档解析完成: docId={}", msg.getDocId());

        } catch (Exception e) {
            log.error("文档解析失败: docId={}", msg.getDocId(), e);
            documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_FAILED);
        }
    }

    /**
     * 解析文档提取纯文本
     * TODO: 接入 Apache Tika，支持 PDF/Word/PPT 等格式
     */
    private String parseDocument(InputStream stream, String fileType) throws Exception {
        // 目前直接读取文本内容，后续替换为 Tika
        return new String(stream.readAllBytes());
    }

}
