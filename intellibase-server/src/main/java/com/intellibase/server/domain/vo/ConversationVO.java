package com.intellibase.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {

    private Long id;

    private Long kbId;

    private String title;

    private String model;

    @JsonRawValue
    private String config;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

}
