package com.intellibase.server.domain.vo;

import com.intellibase.server.domain.dto.ChunkStrategy;
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

    private ChunkStrategy chunkStrategy;

    private Integer docCount;

    private String status;

    private OffsetDateTime createdAt;

}
