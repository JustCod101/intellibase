package com.intellibase.server.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.config.HybridRetrievalProperties;
import com.intellibase.server.domain.dto.RetrievalConfig;
import com.intellibase.server.domain.dto.RetrievalPreset;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private RetrievalCacheService retrievalCacheService;

    @Mock
    private ChunkCacheService chunkCacheService;

    @Mock
    private CacheStatsService cacheStatsService;

    @Mock
    private SparseRecallService sparseRecallService;

    private RetrievalService retrievalService;
    private RetrievalConfigResolver retrievalConfigResolver;

    @BeforeEach
    void setUp() {
        retrievalConfigResolver = new RetrievalConfigResolver(new ObjectMapper(), new HybridRetrievalProperties());
        LexicalTokenizer lexicalTokenizer = new LexicalTokenizer();
        HybridRanker hybridRanker = new HybridRanker(lexicalTokenizer, retrievalConfigResolver);
        retrievalService = new RetrievalService(
                documentChunkMapper,
                knowledgeBaseMapper,
                retrievalCacheService,
                chunkCacheService,
                cacheStatsService,
                retrievalConfigResolver,
                sparseRecallService,
                lexicalTokenizer,
                hybridRanker
        );
        ReflectionTestUtils.setField(retrievalService, "topK", 5);
        ReflectionTestUtils.setField(retrievalService, "similarityThreshold", 0.7);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setRetrievalConfig(retrievalConfigResolver.toJson(RetrievalConfig.builder()
                .preset(RetrievalPreset.GENERAL_QA)
                .build()));
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    }

    @Test
    @DisplayName("纯向量检索 - 成功返回匹配结果并生成摘要")
    void retrieve_DenseOnly_Success() {
        float[] queryVector = new float[]{0.1f, 0.2f};
        Long kbId = 1L;

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(100L);
        chunk.setDocId(10L);
        chunk.setContent("这是一个很长的文本内容，用于测试 RetrievalService 是否能正确生成摘要。摘要通常只保留前200个字符。");
        chunk.setSimilarity(0.85);

        when(documentChunkMapper.findSimilar(anyString(), eq(kbId), anyDouble(), anyInt()))
                .thenReturn(List.of(chunk));
        when(chunkCacheService.getChunks(List.of(100L)))
                .thenReturn(List.of(chunk));

        List<RetrievalResult> results = retrievalService.retrieve(queryVector, kbId);

        assertNotNull(results);
        assertEquals(1, results.size());
        RetrievalResult result = results.get(0);
        assertEquals(100L, result.getChunkId());
        assertEquals(chunk.getContent(), result.getContent());
        assertEquals(0.85, result.getScore(), 0.001);
        assertEquals("DENSE", result.getMatchType());
        assertNotNull(result.getSnippet());

        verify(documentChunkMapper).findSimilar(contains("0.1"), eq(kbId), eq(0.7), eq(5));
        verify(sparseRecallService, never()).recall(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("混合检索 - 融合 sparse 结果并写入配置感知缓存")
    void retrieve_Hybrid_Success() {
        float[] queryVector = new float[]{0.1f, 0.2f};
        Long kbId = 1L;
        String query = "如何处理 HTTP_409 冲突";
        String configHash = retrievalConfigResolver.hash(retrievalConfigResolver.defaultConfig());

        DocumentChunk denseHit = new DocumentChunk();
        denseHit.setId(100L);
        denseHit.setDocId(10L);
        denseHit.setSimilarity(0.81);

        DocumentChunk sparseHit = new DocumentChunk();
        sparseHit.setId(200L);
        sparseHit.setDocId(20L);
        sparseHit.setLexicalScore(0.72);

        DocumentChunk denseChunk = new DocumentChunk();
        denseChunk.setId(100L);
        denseChunk.setDocId(10L);
        denseChunk.setContent("普通冲突处理说明。");

        DocumentChunk sparseChunk = new DocumentChunk();
        sparseChunk.setId(200L);
        sparseChunk.setDocId(20L);
        sparseChunk.setContent("HTTP_409 冲突可以通过 order.create 幂等键重试。");

        when(retrievalCacheService.tryGetCachedResults(query, kbId, configHash))
                .thenReturn(Optional.empty());
        when(documentChunkMapper.findSimilar(anyString(), eq(kbId), eq(0.25), eq(20)))
                .thenReturn(List.of(denseHit));
        when(sparseRecallService.recall(contains("http_409"), eq(kbId), eq(20)))
                .thenReturn(List.of(sparseHit));
        when(chunkCacheService.getChunks(List.of(100L, 200L)))
                .thenReturn(List.of(denseChunk, sparseChunk));

        List<RetrievalResult> results = retrievalService.retrieve(queryVector, kbId, query);

        assertEquals(2, results.size());
        assertEquals(200L, results.get(0).getChunkId());
        assertEquals("SPARSE", results.get(0).getMatchType());
        assertTrue(results.get(0).getScore() >= results.get(1).getScore());

        verify(retrievalCacheService).tryGetCachedResults(query, kbId, configHash);
        verify(retrievalCacheService).cacheResults(eq(query), eq(kbId), eq(configHash), eq(results));
    }

    @Test
    @DisplayName("向量检索 - 无匹配结果时返回空列表")
    void retrieve_NoResults() {
        float[] queryVector = new float[]{0.1f};
        when(documentChunkMapper.findSimilar(anyString(), anyLong(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        List<RetrievalResult> results = retrievalService.retrieve(queryVector, 1L);

        assertTrue(results.isEmpty());
    }
}
