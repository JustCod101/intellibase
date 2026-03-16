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
public class KnowledgeBaseVO {

    private Long id;

    private String name;

    private String description;

    private String embeddingModel;

    @JsonRawValue
    private String chunkStrategy;

    private Integer docCount;

    private String status;

    private OffsetDateTime createdAt;

}
