package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.intellibase.server.config.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "knowledge_base", autoResultMap = true)
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long tenantId;

    private String embeddingModel;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String chunkStrategy;

    private Integer docCount;

    private String status;

    private Long createdBy;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;

}
