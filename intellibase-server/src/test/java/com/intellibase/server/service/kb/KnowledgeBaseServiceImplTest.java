package com.intellibase.server.service.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.dto.ChunkStrategy;
import com.intellibase.server.domain.dto.CreateKbRequest;
import com.intellibase.server.domain.dto.UpdateKbRequest;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.domain.vo.KnowledgeBaseVO;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import com.intellibase.server.service.doc.ChunkStrategyResolver;
import com.intellibase.server.service.rag.CacheEvictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceImplTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private CacheEvictionService cacheEvictionService;

    private KnowledgeBaseServiceImpl knowledgeBaseService;
    private ChunkStrategyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChunkStrategyResolver(new ObjectMapper());
        knowledgeBaseService = new KnowledgeBaseServiceImpl(knowledgeBaseMapper, cacheEvictionService, resolver);
    }

    @Test
    void create_NormalizesChunkStrategyBeforePersisting() {
        CreateKbRequest request = new CreateKbRequest();
        request.setName("制度库");
        request.setDescription("测试知识库");
        request.setChunkStrategy(ChunkStrategy.builder()
                .size(900)
                .overlap(150)
                .build());

        doAnswer(invocation -> {
            KnowledgeBase kb = invocation.getArgument(0);
            kb.setId(11L);
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBase.class));

        KnowledgeBaseVO result = knowledgeBaseService.create(request, 100L);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        ChunkStrategy persisted = resolver.fromJson(captor.getValue().getChunkStrategy());

        assertEquals(900, persisted.getSize());
        assertEquals(150, persisted.getOverlap());
        assertEquals("STRUCTURE_AWARE", persisted.getType());
        assertNotNull(result.getChunkStrategy());
        assertEquals(900, result.getChunkStrategy().getSize());
    }

    @Test
    void update_StoresStructuredChunkStrategyAndReturnsNormalizedObject() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(11L);
        kb.setName("制度库");
        kb.setDescription("旧描述");
        kb.setStatus("ACTIVE");
        kb.setEmbeddingModel("text-embedding-3-small");
        kb.setChunkStrategy("{\"size\":512,\"overlap\":64}");
        when(knowledgeBaseMapper.selectById(11L)).thenReturn(kb);

        UpdateKbRequest request = new UpdateKbRequest();
        request.setDescription("新描述");
        request.setChunkStrategy(ChunkStrategy.builder()
                .size(1000)
                .overlap(100)
                .minSize(300)
                .normalizeWhitespace(false)
                .build());

        KnowledgeBaseVO result = knowledgeBaseService.update(11L, request);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(captor.capture());
        ChunkStrategy persisted = resolver.fromJson(captor.getValue().getChunkStrategy());

        assertEquals(1000, persisted.getSize());
        assertEquals(300, persisted.getMinSize());
        assertFalse(persisted.getNormalizeWhitespace());
        assertEquals("新描述", result.getDescription());
        assertEquals(1000, result.getChunkStrategy().getSize());
        assertFalse(result.getChunkStrategy().getNormalizeWhitespace());
    }
}
