package com.intellibase.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageVO {

    private Long id;

    private Long conversationId;

    /** user / assistant */
    private String role;

    private String content;

    @JsonRawValue
    private String tokenUsage;

    @JsonRawValue
    private String sources;

    private Integer latencyMs;

    private LocalDateTime createdAt;

}
