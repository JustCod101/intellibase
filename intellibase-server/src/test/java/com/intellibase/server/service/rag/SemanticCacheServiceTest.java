package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.SemanticCache;
import com.intellibase.server.mapper.SemanticCacheMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 语义缓存命中后 sanity check 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private SemanticCacheMapper semanticCacheMapper;

    @Mock
    private CacheStatsService cacheStatsService;

    private SemanticCacheService semanticCacheService;

    @BeforeEach
    void setUp() {
        semanticCacheService = new SemanticCacheService(
                semanticCacheMapper,
                cacheStatsService,
                new LexicalTokenizer()
        );
        ReflectionTestUtils.setField(semanticCacheService, "cacheThreshold", 0.95);
        ReflectionTestUtils.setField(semanticCacheService, "cacheSanityCheckEnabled", true);
        ReflectionTestUtils.setField(semanticCacheService, "cacheSanityMinTokenOverlap", 0.25);
        ReflectionTestUtils.setField(semanticCacheService, "cacheSanityCandidateLimit", 3);
    }

    @Test
    @DisplayName("语义缓存 - 跳过 Top1 词面不相关候选，命中后续安全候选")
    void tryGetCachedAnswer_SkipsUnsafeCandidateAndUsesNextSafeHit() {
        SemanticCache unsafeTop1 = cache(10L, "买房贷款政策", "错误答案");
        SemanticCache safeSecond = cache(11L, "怎么请假", "请假需要提交审批单。");
        when(semanticCacheMapper.findSimilar(anyString(), eq(1L), anyDouble(), eq(3)))
                .thenReturn(List.of(unsafeTop1, safeSecond));

        Optional<String> answer = semanticCacheService.tryGetCachedAnswer(
                "请假流程怎么走",
                1L,
                new float[]{0.1f, 0.2f}
        );

        assertEquals(Optional.of("请假需要提交审批单。"), answer);
        verify(semanticCacheMapper).incrementHitCount(11L);
        verify(semanticCacheMapper, never()).incrementHitCount(10L);
        verify(cacheStatsService).recordL1Hit();
        verify(cacheStatsService, never()).recordL1Miss();
    }

    @Test
    @DisplayName("语义缓存 - 向量相似但词面锚点不重叠时拒绝命中")
    void tryGetCachedAnswer_RejectsVectorFalsePositive() {
        SemanticCache unsafeTop1 = cache(10L, "买房贷款政策", "错误答案");
        when(semanticCacheMapper.findSimilar(anyString(), eq(1L), anyDouble(), eq(3)))
                .thenReturn(List.of(unsafeTop1));

        Optional<String> answer = semanticCacheService.tryGetCachedAnswer(
                "请假流程怎么走",
                1L,
                new float[]{0.1f, 0.2f}
        );

        assertTrue(answer.isEmpty());
        verify(semanticCacheMapper, never()).incrementHitCount(10L);
        verify(cacheStatsService).recordL1Miss();
        verify(cacheStatsService, never()).recordL1Hit();
    }

    @Test
    @DisplayName("语义缓存 - 关闭 sanity check 后保持原 Top1 命中行为")
    void tryGetCachedAnswer_WhenSanityDisabled_UsesTop1() {
        ReflectionTestUtils.setField(semanticCacheService, "cacheSanityCheckEnabled", false);
        SemanticCache unsafeTop1 = cache(10L, "买房贷款政策", "历史答案");
        when(semanticCacheMapper.findSimilar(anyString(), eq(1L), anyDouble(), eq(1)))
                .thenReturn(List.of(unsafeTop1));

        Optional<String> answer = semanticCacheService.tryGetCachedAnswer(
                "请假流程怎么走",
                1L,
                new float[]{0.1f, 0.2f}
        );

        assertEquals(Optional.of("历史答案"), answer);
        verify(semanticCacheMapper).incrementHitCount(10L);
        verify(cacheStatsService).recordL1Hit();
        verify(cacheStatsService, never()).recordL1Miss();
    }

    private SemanticCache cache(Long id, String queryText, String responseText) {
        SemanticCache cache = new SemanticCache();
        cache.setId(id);
        cache.setKbId(1L);
        cache.setQueryText(queryText);
        cache.setResponseText(responseText);
        return cache;
    }
}
