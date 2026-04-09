package com.intellibase.server.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 混合检索配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalConfig {

    private RetrievalPreset preset;

    private Boolean hybridEnabled;

    private Boolean rerankEnabled;

    private Integer denseTopK;

    private Integer sparseTopK;

    private Integer fusionTopK;

    private Integer finalTopK;

    private Double denseWeight;

    private Double sparseWeight;
}
