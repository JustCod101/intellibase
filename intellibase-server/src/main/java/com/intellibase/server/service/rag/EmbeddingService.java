package com.intellibase.server.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 向量化服务
 * 使用 LangChain4j 调用 OpenAI Embedding API，支持单条和批量向量化
 */
@Slf4j
@Service
public class EmbeddingService {

    @Value("${embedding.api-key}")
    private String apiKey;

    @Value("${embedding.base-url}")
    private String baseUrl;

    @Value("${embedding.model-name}")
    private String modelName;

    @Value("${embedding.dimensions}")
    private int dimensions;

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dimensions)
                .build();
        log.info("EmbeddingService 初始化完成: model={}, dimensions={}", modelName, dimensions);
    }

    /**
     * 单条文本向量化
     */
    public float[] embed(String text) {
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vector();
    }

    /**
     * 批量文本向量化
     *
     * @param texts 文本列表
     * @return 对应的向量列表（顺序与输入一致）
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();

        Response<List<Embedding>> response = embeddingModel.embedAll(segments);

        return response.content().stream()
                .map(Embedding::vector)
                .toList();
    }

    /**
     * 将 float[] 向量转为 pgvector 所需的字符串格式 "[0.1,0.2,...]"
     */
    public static String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

}
