package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 向量检索服务
 * <p>
 * 职责：在海量的文档片段中，找到与用户问题在”语义”上最接近的内容。
 * 技术栈：基于 pgvector 插件实现的余弦相似度检索。
 * <p>
 * 缓存策略（L2 + L3）：
 * - L2：检索缓存，相同 query hash 的检索结果直接返回，避免重复向量检索
 * - L3：文档缓存，热点 chunk 内容缓存到 Redis，减少数据库读取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final DocumentChunkMapper documentChunkMapper;
    private final RetrievalCacheService retrievalCacheService;
    private final ChunkCacheService chunkCacheService;
    private final CacheStatsService cacheStatsService;

    @Value("${rag.retrieval-top-k}")
    private int topK;

    @Value("${rag.similarity-threshold}")
    private double similarityThreshold;

    /**
     * 执行向量检索（集成 L2 检索缓存 + L3 文档缓存）
     *
     * @param queryVector 用户问题的向量
     * @param kbId        知识库ID
     * @param query       原始查询文本（用于 L2 缓存 Key 计算）
     * @return 返回最相关的文档片段列表
     */
    public List<RetrievalResult> retrieve(float[] queryVector, Long kbId, String query) {
        // ===== L2: 检索缓存（需要原始 query 文本来计算缓存 Key）=====
        if (query != null) {
            Optional<List<RetrievalResult>> l2Cached = retrievalCacheService.tryGetCachedResults(query, kbId);
            if (l2Cached.isPresent()) {
                cacheStatsService.recordL2Hit();
                log.info("L2 检索缓存命中: kbId={}, 结果数={}", kbId, l2Cached.get().size());
                return l2Cached.get();
            }
            cacheStatsService.recordL2Miss();
        }

        // ===== L3 + DB: 向量检索 =====
        String vectorStr = EmbeddingService.toVectorString(queryVector);
        List<DocumentChunk> chunks = documentChunkMapper.findSimilar(
                vectorStr, kbId, similarityThreshold, topK);

        cacheStatsService.recordDbQuery();

        // 使用 L3 缓存预热检索到的热点 chunk
        if (!chunks.isEmpty()) {
            chunkCacheService.warmUp(chunks);
        }

        List<RetrievalResult> results = chunks.stream()
                .map(this::toResult)
                .toList();

        // 写入 L2 缓存供后续相同查询使用
        if (query != null && !results.isEmpty()) {
            retrievalCacheService.cacheResults(query, kbId, results);
        }

        log.info("向量检索完成: kbId={}, 命中数={}", kbId, results.size());
        return results;
    }

    /**
     * 执行向量检索（无 L2 缓存，兼容旧调用）
     */
    public List<RetrievalResult> retrieve(float[] queryVector, Long kbId) {
        return retrieve(queryVector, kbId, null);
    }

    private RetrievalResult toResult(DocumentChunk chunk) {
        String content = chunk.getContent();
        String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;

        return RetrievalResult.builder()
                .chunkId(chunk.getId())
                .docId(chunk.getDocId())
                .score(0)
                .content(content)
                .snippet(snippet)
                .build();
    }

}
