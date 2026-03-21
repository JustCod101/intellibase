package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.intellibase.server.config.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "document_chunk", autoResultMap = true)
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long kbId;

    /** 块在文档中的序号 */
    private Integer chunkIndex;

    /** 原始文本 */
    private String content;

    /** Token 数量 */
    private Integer tokenCount;

    /**
     * 向量数据（pgvector vector(1536) 类型）
     * 在 MyBatis 层以字符串形式传递，格式为 "[0.1,0.2,...]"
     */
    @TableField(exist = false)
    private float[] embedding;

    /** JSONB — 页码、标题层级等 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadata;

    /** 向量检索时由 SQL 计算的相似度分数（非持久化字段） */
    @TableField(exist = false)
    private Double similarity;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

}
