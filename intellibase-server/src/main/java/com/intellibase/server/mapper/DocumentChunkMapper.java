package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 批量插入文档分块（含 pgvector 向量）
     * 使用原生 SQL 处理 vector 类型的强制转换
     */
    void batchInsertWithVector(@Param("list") List<DocumentChunk> chunks,
                               @Param("embeddings") List<String> embeddings);

    /**
     * 向量相似度检索
     *
     * @param queryEmbedding 查询向量（字符串格式 "[0.1,0.2,...]"）
     * @param kbId           知识库ID（多租户过滤）
     * @param threshold      相似度阈值（0~1）
     * @param limit          返回数量
     * @return 按相似度降序排列的分块列表
     */
    List<DocumentChunk> findSimilar(@Param("queryEmbedding") String queryEmbedding,
                                    @Param("kbId") Long kbId,
                                    @Param("threshold") double threshold,
                                    @Param("limit") int limit);

}
