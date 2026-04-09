package com.intellibase.server.service.rag;

import com.intellibase.server.domain.dto.RetrievalConfig;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RetrievalCacheService retrievalCacheService;
    private final ChunkCacheService chunkCacheService;
    private final CacheStatsService cacheStatsService;
    private final RetrievalConfigResolver retrievalConfigResolver;
    private final SparseRecallService sparseRecallService;
    private final LexicalTokenizer lexicalTokenizer;
    private final HybridRanker hybridRanker;

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
        RetrievalConfig config = resolveConfig(kbId);
        String configHash = retrievalConfigResolver.hash(config);

        // ===== L2: 检索缓存（需要原始 query 文本来计算缓存 Key）=====
        if (StringUtils.hasText(query)) {
            Optional<List<RetrievalResult>> l2Cached = retrievalCacheService.tryGetCachedResults(query, kbId, configHash);
            if (l2Cached.isPresent()) {
                cacheStatsService.recordL2Hit();
                log.info("L2 检索缓存命中: kbId={}, 结果数={}", kbId, l2Cached.get().size());
                return l2Cached.get();
            }
            cacheStatsService.recordL2Miss();
        }

        List<RetrievalResult> results = config.getHybridEnabled() && StringUtils.hasText(query)
                ? retrieveHybrid(queryVector, kbId, query, config)
                : retrieveDenseOnly(queryVector, kbId, config);

        if (results.isEmpty()) {
            if (StringUtils.hasText(query)) {
                retrievalCacheService.cacheResults(query, kbId, configHash, List.of());
            }
            log.info("向量检索完成: kbId={}, 命中数=0", kbId);
            return List.of();
        }

        // 写入 L2 缓存供后续相同查询使用
        if (StringUtils.hasText(query)) {
            retrievalCacheService.cacheResults(query, kbId, configHash, results);
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

    private List<RetrievalResult> retrieveDenseOnly(float[] queryVector, Long kbId, RetrievalConfig config) {
        List<DocumentChunk> denseHits = denseRecall(queryVector, kbId, similarityThreshold, config.getFinalTopK());
        if (denseHits.isEmpty()) {
            return List.of();
        }

        Map<Long, DocumentChunk> chunkMap = loadChunks(denseHits.stream().map(DocumentChunk::getId).toList());
        return denseHits.stream()
                .map(hit -> toResult(chunkMap.get(hit.getId()), hit.getDocId(), hit.getSimilarity(), null))
                .filter(result -> StringUtils.hasText(result.getContent()))
                .sorted(Comparator.comparingDouble(RetrievalResult::getScore).reversed())
                .limit(config.getFinalTopK())
                .toList();
    }

    private List<RetrievalResult> retrieveHybrid(float[] queryVector, Long kbId, String query, RetrievalConfig config) {
        List<DocumentChunk> denseHits = denseRecall(queryVector, kbId,
                retrievalConfigResolver.getDenseSimilarityThreshold(), config.getDenseTopK());
        String lexicalQuery = lexicalTokenizer.buildLexicalQuery(query);
        List<DocumentChunk> sparseHits = sparseRecallService.recall(lexicalQuery, kbId, config.getSparseTopK());
        if (StringUtils.hasText(lexicalQuery)) {
            cacheStatsService.recordDbQuery();
        }

        if (denseHits.isEmpty() && sparseHits.isEmpty()) {
            return List.of();
        }

        Map<Long, RetrievalResult> candidates = new LinkedHashMap<>();
        for (DocumentChunk hit : denseHits) {
            RetrievalResult result = candidates.computeIfAbsent(hit.getId(),
                    chunkId -> emptyCandidate(chunkId, hit.getDocId()));
            result.setDenseScore(hit.getSimilarity());
        }
        for (DocumentChunk hit : sparseHits) {
            RetrievalResult result = candidates.computeIfAbsent(hit.getId(),
                    chunkId -> emptyCandidate(chunkId, hit.getDocId()));
            result.setSparseScore(hit.getLexicalScore());
        }

        List<Long> unionChunkIds = new ArrayList<>(candidates.keySet());
        Map<Long, DocumentChunk> chunkMap = loadChunks(unionChunkIds);
        candidates.values().removeIf(candidate -> !hydrateCandidate(candidate, chunkMap));

        List<RetrievalResult> ranked = hybridRanker.rank(
                query,
                new ArrayList<>(candidates.values()),
                denseHits.stream().map(DocumentChunk::getId).toList(),
                sparseHits.stream().map(DocumentChunk::getId).toList(),
                config);

        return ranked.stream()
                .limit(config.getFinalTopK())
                .toList();
    }

    private List<DocumentChunk> denseRecall(float[] queryVector, Long kbId, double threshold, int limit) {
        String vectorStr = EmbeddingService.toVectorString(queryVector);
        List<DocumentChunk> searchHits = documentChunkMapper.findSimilar(vectorStr, kbId, threshold, limit);
        cacheStatsService.recordDbQuery();
        return searchHits;
    }

    private Map<Long, DocumentChunk> loadChunks(List<Long> chunkIds) {
        List<DocumentChunk> chunks = chunkCacheService.getChunks(chunkIds);
        Map<Long, DocumentChunk> chunkMap = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            chunkMap.put(chunk.getId(), chunk);
        }
        return chunkMap;
    }

    private RetrievalResult emptyCandidate(Long chunkId, Long docId) {
        return RetrievalResult.builder()
                .chunkId(chunkId)
                .docId(docId)
                .build();
    }

    private boolean hydrateCandidate(RetrievalResult candidate, Map<Long, DocumentChunk> chunkMap) {
        DocumentChunk chunk = chunkMap.get(candidate.getChunkId());
        if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
            return false;
        }
        candidate.setDocId(chunk.getDocId() != null ? chunk.getDocId() : candidate.getDocId());
        candidate.setContent(chunk.getContent());
        candidate.setSnippet(buildSnippet(chunk.getContent()));
        return true;
    }

    private RetrievalResult toResult(DocumentChunk chunk, Long docId, Double denseScore, Double sparseScore) {
        if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
            return RetrievalResult.builder().build();
        }
        String content = chunk.getContent();
        double finalScore = denseScore != null ? denseScore : (sparseScore != null ? sparseScore : 0D);

        return RetrievalResult.builder()
                .chunkId(chunk.getId())
                .docId(docId)
                .score(finalScore)
                .denseScore(denseScore)
                .sparseScore(sparseScore)
                .fusedScore(finalScore)
                .rerankScore(finalScore)
                .matchType(denseScore != null && sparseScore != null ? "HYBRID" : (denseScore != null ? "DENSE" : "SPARSE"))
                .content(content)
                .snippet(buildSnippet(content))
                .build();
    }

    private RetrievalConfig resolveConfig(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            return retrievalConfigResolver.defaultConfig();
        }
        return retrievalConfigResolver.fromJson(kb.getRetrievalConfig());
    }

    private String buildSnippet(String content) {
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }
}
