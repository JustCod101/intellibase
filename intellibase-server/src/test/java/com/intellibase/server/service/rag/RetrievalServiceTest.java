package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.mapper.DocumentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 向量检索服务 (RetrievalService) 单元测试
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @InjectMocks
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        // 通过反射设置私有字段（模拟 @Value 注入）
        ReflectionTestUtils.setField(retrievalService, "topK", 5);
        ReflectionTestUtils.setField(retrievalService, "similarityThreshold", 0.7);
    }

    @Test
    @DisplayName("向量检索 - 成功返回匹配结果并生成摘要")
    void retrieve_Success() {
        // 1. 准备模拟数据
        float[] queryVector = new float[]{0.1f, 0.2f};
        Long kbId = 1L;

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(100L);
        chunk.setDocId(10L);
        chunk.setContent("这是一个很长的文本内容，用于测试 RetrievalService 是否能正确生成摘要。摘要通常只保留前200个字符。");

        when(documentChunkMapper.findSimilar(anyString(), eq(kbId), anyDouble(), anyInt()))
                .thenReturn(List.of(chunk));

        // 2. 执行检索
        List<RetrievalResult> results = retrievalService.retrieve(queryVector, kbId);

        // 3. 验证结果
        assertNotNull(results);
        assertEquals(1, results.size());
        RetrievalResult result = results.get(0);
        assertEquals(100L, result.getChunkId());
        assertEquals(chunk.getContent(), result.getContent());
        assertNotNull(result.getSnippet());
        
        // 验证调用参数
        verify(documentChunkMapper).findSimilar(
                contains("0.1"), eq(kbId), eq(0.7), eq(5));
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
