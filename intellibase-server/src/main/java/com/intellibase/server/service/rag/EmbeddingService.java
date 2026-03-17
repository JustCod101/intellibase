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

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化 (Embedding) 服务
 * <p>
 * 核心原理：使用大模型（如 text-embedding-3-small）将一段文字转成一串固定维度的数字（向量）。
 * 作用：让机器能通过数学计算，判断两个词或句子的“语义”是否相近。
 */
@Slf4j
@Service
public class EmbeddingService {

    // 读取 API 密钥和地址
    @Value("${embedding.api-key}")
    private String apiKey;

    @Value("${embedding.base-url}")
    private String baseUrl;

    @Value("${embedding.model-name}")
    private String modelName;

    // 向量的维度（如：1536），代表语义特征的精细程度
    @Value("${embedding.dimensions}")
    private int dimensions;

    // LangChain4j 提供的统一模型接口
    private EmbeddingModel embeddingModel;

    /**
     * 初始化：在项目启动后创建 Embedding 模型客户端
     */
    @PostConstruct
    public void init() {
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dimensions)
                .build();
        log.info("EmbeddingService 初始化完成: 使用模型={}, 维度={}", modelName, dimensions);
    }

    /**
     * 将单段文本向量化
     * 常用场景：处理用户提问
     * 
     * @param text 输入文本
     * @return 返回浮点数组（即向量）
     */
    public float[] embed(String text) {
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vector();
    }

    /**
     * 批量文本向量化
     * 常用场景：上传文档时，对成百上千个文档分块进行批量转换
     *
     * @param texts 文本列表
     * @return 对应的向量列表，顺序与输入严格一致
     */
    private static final int MAX_BATCH_SIZE = 10;

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> allVectors = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));

            List<TextSegment> segments = batch.stream()
                    .map(TextSegment::from)
                    .toList();

            Response<List<Embedding>> response = embeddingModel.embedAll(segments);

            response.content().stream()
                    .map(Embedding::vector)
                    .forEach(allVectors::add);

            log.debug("Embedding 批次 {}/{} 完成", (i / MAX_BATCH_SIZE) + 1,
                    (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE);
        }

        return allVectors;
    }

    /**
     * 向量格式转换工具
     * 
     * 数据库（pgvector）存储向量时，SQL 语法要求是字符串格式："[0.1, 0.2, 0.3...]"
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
