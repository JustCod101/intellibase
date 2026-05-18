package com.intellibase.server.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 两层缓存失效单元测试。
 */
@ExtendWith(MockitoExtension.class)
class CacheEvictionServiceTest {

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private RetrievalCacheService retrievalCacheService;

    @InjectMocks
    private CacheEvictionService cacheEvictionService;

    @Test
    @DisplayName("知识库删除 - 仅失效 L1 语义缓存与 L2 检索缓存")
    void evictAllByKbId_EvictsL1AndL2() {
        cacheEvictionService.evictAllByKbId(42L);

        verify(semanticCacheService).invalidateByKbId(42L);
        verify(retrievalCacheService).invalidateByKbId(42L);
        verifyNoMoreInteractions(semanticCacheService, retrievalCacheService);
    }

    @Test
    @DisplayName("文档更新 - 按 kbId 失效 L1 语义缓存与 L2 检索缓存")
    void evictByDocument_EvictsByKnowledgeBase() {
        cacheEvictionService.evictByDocument(1001L, 42L);

        verify(semanticCacheService).invalidateByKbId(42L);
        verify(retrievalCacheService).invalidateByKbId(42L);
        verifyNoMoreInteractions(semanticCacheService, retrievalCacheService);
    }
}
