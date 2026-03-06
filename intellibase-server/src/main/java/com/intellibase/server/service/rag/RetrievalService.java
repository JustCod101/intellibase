package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索服务
 * <p>
 * 职责：在海量的文档片段中，找到与用户问题在“语义”上最接近的内容。
 * 技术栈：基于 pgvector 插件实现的余弦相似度检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    // 数据库操作对象，专门负责查询 document_chunk 表
    private final DocumentChunkMapper documentChunkMapper;

    // 检索返回的最大片段数量（Top K）
    @Value("${rag.retrieval-top-k}")
    private int topK;

    // 相似度阈值：只有相似度超过这个值的片段才会被认为是相关的
    @Value("${rag.similarity-threshold}")
    private double similarityThreshold;

    /**
     * 执行向量检索
     *
     * @param queryVector 用户问题的向量（一串代表语义的数字）
     * @param kbId        知识库ID（实现多租户/多库隔离，只在指定的库里找）
     * @return 返回最相关的文档片段列表
     */
    public List<RetrievalResult> retrieve(float[] queryVector, Long kbId) {
        // 1. 将浮点数组转为数据库识别的向量字符串，如 "[0.1, 0.2, ...]"
        String vectorStr = EmbeddingService.toVectorString(queryVector);

        // 2. 调用 Mapper 执行 SQL 查询
        // SQL 内部使用 <=> (余弦距离) 运算符计算相似度，并按距离从小到大排序
        List<DocumentChunk> chunks = documentChunkMapper.findSimilar(
                vectorStr, kbId, similarityThreshold, topK);

        // 3. 将数据库实体转换为 RAG 专用结果对象，并生成精简摘要
        List<RetrievalResult> results = chunks.stream()
                .map(this::toResult)
                .toList();

        log.info("向量检索完成: kbId={}, 检索到命中数={}", kbId, results.size());
        return results;
    }

    /**
     * 将数据库记录转换为前端和 LLM 使用的 VO 对象
     */
    private RetrievalResult toResult(DocumentChunk chunk) {
        String content = chunk.getContent();
        // 截取前 200 字作为摘要，防止单个片段过大导致前端渲染压力
        String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;

        return RetrievalResult.builder()
                .chunkId(chunk.getId())
                .docId(chunk.getDocId())
                .score(0) // 注意：相似度分数目前由 SQL 计算，暂未回填至 Entity 字段
                .content(content)
                .snippet(snippet)
                .build();
    }

}
