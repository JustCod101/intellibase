package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("semantic_cache")
public class SemanticCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    /** pgvector vector(1536) — 以字符串传递 */
    @TableField(exist = false)
    private String queryEmbedding;

    private String queryText;

    private String responseText;

    private Integer hitCount;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    private OffsetDateTime expiresAt;

}
