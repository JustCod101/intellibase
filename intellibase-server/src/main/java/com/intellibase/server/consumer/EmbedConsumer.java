package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.rag.CacheEvictionService;
import com.intellibase.server.service.rag.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化消费者
 * 监听 doc.embed.queue，批量调用 Embedding API 并写入 pgvector
 *
 * <p>重试策略（由 RabbitConfig 中的 RetryInterceptor 提供）：
 * <ul>
 *   <li>Embedding API 限流/超时、数据库临时不可用 —— 抛出异常，触发指数退避重试</li>
 *   <li>重试耗尽 —— 消息被 RepublishMessageRecoverer 发送到 DLQ，由 DlqConsumer 标记 FAILED</li>
 * </ul>
 *
 * 流程：
 * 1. 从消息中取出文本块列表
 * 2. 批量调用 Embedding API 获取向量
 * 3. 组装 DocumentChunk 实体，批量写入 document_chunk 表（含 pgvector vector 类型）
 * 4. 如果是最后一批，更新 document 状态为 COMPLETED 并记录总分块数
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbedConsumer {

    private final EmbeddingService embeddingService;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;
    private final CacheEvictionService cacheEvictionService;

    @RabbitListener(queues = Constants.QUEUE_DOC_EMBED, concurrency = "2-3")
    public void handleEmbed(EmbedBatchMessage msg) {
        log.info("收到向量化消息: docId={}, kbId={}, chunkCount={}, lastBatch={}",
                msg.getDocId(), msg.getKbId(), msg.getChunks().size(), msg.isLastBatch());

        List<TextChunk> textChunks = msg.getChunks();

        // ===== 1. 提取所有文本，批量调用 Embedding API =====
        List<String> texts = textChunks.stream()
                .map(TextChunk::getContent)
                .toList();

        List<float[]> vectors = embeddingService.embedBatch(texts);
        log.info("Embedding API 调用完成: docId={}, 向量数={}", msg.getDocId(), vectors.size());

        // ===== 2. 组装 DocumentChunk 实体列表 =====
        List<DocumentChunk> chunks = new ArrayList<>(textChunks.size());
        List<String> embeddings = new ArrayList<>(textChunks.size());

        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk tc = textChunks.get(i);
            float[] vector = vectors.get(i);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(msg.getDocId());
            chunk.setKbId(msg.getKbId());
            chunk.setChunkIndex(tc.getIndex());
            chunk.setContent(tc.getContent());
            chunk.setTokenCount(tc.getTokenCount());

            chunks.add(chunk);
            embeddings.add(EmbeddingService.toVectorString(vector));
        }

        // ===== 3. 批量写入 document_chunk 表（含 pgvector 向量） =====
        documentChunkMapper.batchInsertWithVector(chunks, embeddings);
        log.info("分块向量写入完成: docId={}, 写入数={}", msg.getDocId(), chunks.size());

        // ===== 4. 如果是最后一批，更新文档状态为 COMPLETED，并清除缓存 =====
        if (msg.isLastBatch()) {
            documentMapper.updateChunkCount(
                    msg.getDocId(), msg.getTotalChunks(), Constants.DOC_STATUS_COMPLETED);
            // 新文档入库后，清除 L1/L2 缓存，确保后续查询能检索到新内容
            cacheEvictionService.evictByDocument(msg.getDocId(), msg.getKbId());
            log.info("文档向量化全部完成: docId={}, totalChunks={}", msg.getDocId(), msg.getTotalChunks());
        }
        // 所有异常（Embedding API 限流/超时、DB 写入失败等）自然抛出，
        // 由 RetryInterceptor 捕获并按指数退避重试；耗尽后进入 DLQ
    }

}
