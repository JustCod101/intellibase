package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.intellibase.server.config.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / system */
    private String role;

    private String content;

    /** JSONB — {promptTokens, completionTokens} */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String tokenUsage;

    /** JSONB — [{chunkId, score, snippet}] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sources;

    /** 响应耗时（毫秒） */
    private Integer latencyMs;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

}
