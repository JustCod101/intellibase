package com.intellibase.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.intellibase.server.domain.dto.RetrievalPreset;

/**
 * 场景化混合检索默认配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.hybrid")
public class HybridRetrievalProperties {

    private RetrievalPreset defaultPreset = RetrievalPreset.GENERAL_QA;

    private int pipelineVersion = 1;

    private double denseSimilarityThreshold = 0.25;

    private int rrfK = 60;

    private PresetProperties generalQa = new PresetProperties(true, true, 20, 20, 15, 5, 0.55, 0.45);

    private PresetProperties exactLookup = new PresetProperties(true, true, 10, 30, 15, 5, 0.35, 0.65);

    private PresetProperties longformSynthesis = new PresetProperties(true, true, 30, 15, 20, 8, 0.65, 0.35);

    @Data
    public static class PresetProperties {
        private boolean hybridEnabled = true;
        private boolean rerankEnabled = true;
        private int denseTopK = 20;
        private int sparseTopK = 20;
        private int fusionTopK = 15;
        private int finalTopK = 5;
        private double denseWeight = 0.55;
        private double sparseWeight = 0.45;

        public PresetProperties() {
        }

        public PresetProperties(boolean hybridEnabled,
                                boolean rerankEnabled,
                                int denseTopK,
                                int sparseTopK,
                                int fusionTopK,
                                int finalTopK,
                                double denseWeight,
                                double sparseWeight) {
            this.hybridEnabled = hybridEnabled;
            this.rerankEnabled = rerankEnabled;
            this.denseTopK = denseTopK;
            this.sparseTopK = sparseTopK;
            this.fusionTopK = fusionTopK;
            this.finalTopK = finalTopK;
            this.denseWeight = denseWeight;
            this.sparseWeight = sparseWeight;
        }
    }
}
