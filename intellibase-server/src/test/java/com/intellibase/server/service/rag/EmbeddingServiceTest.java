package com.intellibase.server.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
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
 * 向量化服务 (EmbeddingService) 单元测试
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        // 设置注入的字段和 Mock 模型
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", embeddingModel);
    }

    @Test
    @DisplayName("文本向量化 - 单个文本成功")
    void embed_Success() {
        float[] expectedVector = new float[]{0.1f, 0.2f};
        Embedding embedding = Embedding.from(expectedVector);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));

        float[] vector = embeddingService.embed("Hello World");

        assertArrayEquals(expectedVector, vector);
        verify(embeddingModel).embed("Hello World");
    }

    @Test
    @DisplayName("文本向量化 - 批量文本成功")
    void embedBatch_Success() {
        float[] v1 = new float[]{0.1f, 0.2f};
        float[] v2 = new float[]{0.3f, 0.4f};
        List<Embedding> embeddings = List.of(Embedding.from(v1), Embedding.from(v2));
        
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(embeddings));

        List<float[]> vectors = embeddingService.embedBatch(List.of("text1", "text2"));

        assertEquals(2, vectors.size());
        assertArrayEquals(v1, vectors.get(0));
        assertArrayEquals(v2, vectors.get(1));
        verify(embeddingModel).embedAll(anyList());
    }

    @Test
    @DisplayName("向量格式转换 - 正确转为 pgvector 字符串")
    void toVectorString_FormatCorrect() {
        float[] vector = new float[]{0.1f, -0.5f, 0.0f};
        String str = EmbeddingService.toVectorString(vector);
        assertEquals("[0.1,-0.5,0.0]", str);
    }
}
