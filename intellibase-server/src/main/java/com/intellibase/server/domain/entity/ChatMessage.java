package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / system */
    private String role;

    private String content;

    /** JSONB — {promptTokens, completionTokens} */
    private String tokenUsage;

    /** JSONB — [{chunkId, score, snippet}] */
    private String sources;

    /** 响应耗时（毫秒） */
    private Integer latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

}
