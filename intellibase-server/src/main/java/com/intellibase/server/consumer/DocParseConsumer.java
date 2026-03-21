package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.doc.DocParseService;
import com.intellibase.server.service.doc.TextSplitter;
import com.intellibase.server.service.kb.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析消费者
 * 监听 doc.parse.queue，完成：MinIO 下载 → Tika 解析 → 递归分块 → 批量发送到 embed 队列
 *
 * <p>重试策略（由 RabbitConfig 中的 RetryInterceptor 提供）：
 * <ul>
 *   <li>瞬时故障（网络抖动、服务不可用等）—— 抛出异常，触发指数退避重试（最多 3 次）</li>
 *   <li>永久性故障（文档内容为空、分块为空等业务错误）—— 直接标记 FAILED，不重试</li>
 *   <li>重试耗尽 —— 消息被 RepublishMessageRecoverer 发送到 DLQ，由 DlqConsumer 标记 FAILED</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocParseConsumer {

    private final MinioService minioService;
    private final DocParseService docParseService;
    private final TextSplitter textSplitter;
    private final DocumentMapper documentMapper;
    private final RabbitTemplate rabbitTemplate;

    /** 每批发送到 embed 队列的分块数量 */
    private static final int EMBED_BATCH_SIZE = 100;

    @RabbitListener(queues = Constants.QUEUE_DOC_PARSE, concurrency = "3-5")
    public void handleDocParse(DocParseMessage msg) throws Exception {
        log.info("开始解析文档: docId={}, fileType={}, fileKey={}", msg.getDocId(), msg.getFileType(), msg.getFileKey());

        // ===== 1. 更新状态为 PARSING =====
        documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_PARSING);

        // ===== 2. 从 MinIO 下载文档 =====
        InputStream stream = minioService.downloadFile(msg.getFileKey());

        // ===== 3. Apache Tika 解析，提取纯文本 =====
        String text = docParseService.parse(stream, msg.getFileType());
        log.info("文档文本提取完成: docId={}, 文本长度={}", msg.getDocId(), text.length());

        if (text.isBlank()) {
            log.warn("文档内容为空: docId={}", msg.getDocId());
            documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_FAILED);
            // 内容为空属于不可重试的业务错误，跳过重试直接进入 DLQ
            throw new AmqpRejectAndDontRequeueException("文档内容为空, docId=" + msg.getDocId());
        }

        // ===== 4. RecursiveCharacterTextSplitter 分块 =====
        int chunkSize = msg.getChunkSize() != null ? msg.getChunkSize() : 512;
        int chunkOverlap = msg.getChunkOverlap() != null ? msg.getChunkOverlap() : 64;
        List<TextChunk> chunks = textSplitter.split(text, chunkSize, chunkOverlap);
        log.info("文档分块完成: docId={}, 分块数={}", msg.getDocId(), chunks.size());

        if (chunks.isEmpty()) {
            log.warn("文档分块结果为空: docId={}", msg.getDocId());
            documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_FAILED);
            throw new AmqpRejectAndDontRequeueException("文档分块结果为空, docId=" + msg.getDocId());
        }

        // ===== 5. 更新状态为 EMBEDDING =====
        documentMapper.updateStatus(msg.getDocId(), Constants.DOC_STATUS_EMBEDDING);

        // ===== 6. 按 batch_size=100 批量发送到 doc.embed.queue =====
        List<List<TextChunk>> batches = partition(chunks, EMBED_BATCH_SIZE);
        for (int i = 0; i < batches.size(); i++) {
            boolean isLast = (i == batches.size() - 1);
            EmbedBatchMessage embedMsg = EmbedBatchMessage.builder()
                    .docId(msg.getDocId())
                    .kbId(msg.getKbId())
                    .lastBatch(isLast)
                    .totalChunks(chunks.size())
                    .chunks(batches.get(i))
                    .build();
            rabbitTemplate.convertAndSend(Constants.QUEUE_DOC_EMBED, embedMsg);
        }

        log.info("文档解析完成，已发送 {} 批向量化消息: docId={}", batches.size(), msg.getDocId());
        // 瞬时异常（MinIO 网络超时、Tika 解析临时错误等）会自然抛出，
        // 由 RetryInterceptor 捕获并按指数退避重试
    }

    /**
     * 将列表按指定大小分批
     */
    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

}
