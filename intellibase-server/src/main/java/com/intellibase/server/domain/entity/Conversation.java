package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.intellibase.server.config.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "conversation", autoResultMap = true)
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long kbId;

    private String title;

    /** 使用的大模型 */
    private String model;

    /** JSONB — {"temperature":0.7,"topK":5} */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String config;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;

}
