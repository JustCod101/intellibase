package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.config.HybridRetrievalProperties;
import com.intellibase.server.domain.dto.RetrievalConfig;
import com.intellibase.server.domain.dto.RetrievalPreset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 统一处理 retrievalConfig 的默认值、兼容解析和归一化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalConfigResolver {

    private final ObjectMapper objectMapper;
    private final HybridRetrievalProperties properties;

    public RetrievalConfig defaultConfig() {
        return presetDefaults(properties.getDefaultPreset());
    }

    public RetrievalConfig fromJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return defaultConfig();
        }
        try {
            return normalize(objectMapper.readValue(rawJson, RetrievalConfig.class));
        } catch (Exception e) {
            log.warn("解析 retrievalConfig 失败，回退默认配置: {}", rawJson, e);
            return defaultConfig();
        }
    }

    public String toJson(RetrievalConfig config) {
        try {
            return objectMapper.writeValueAsString(normalize(config));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化 retrievalConfig 失败", e);
        }
    }

    public RetrievalConfig normalize(RetrievalConfig raw) {
        RetrievalPreset preset = raw != null && raw.getPreset() != null
                ? raw.getPreset()
                : properties.getDefaultPreset();
        RetrievalConfig defaults = presetDefaults(preset);
        if (raw == null) {
            return defaults;
        }

        int denseTopK = positiveOrDefault(raw.getDenseTopK(), defaults.getDenseTopK());
        int sparseTopK = positiveOrDefault(raw.getSparseTopK(), defaults.getSparseTopK());
        int fusionTopK = positiveOrDefault(raw.getFusionTopK(), defaults.getFusionTopK());
        int maxFusionTopK = Math.max(1, denseTopK + sparseTopK);
        fusionTopK = clamp(fusionTopK, 1, maxFusionTopK);

        int finalTopK = positiveOrDefault(raw.getFinalTopK(), defaults.getFinalTopK());
        finalTopK = clamp(finalTopK, 1, fusionTopK);

        double denseWeight = raw.getDenseWeight() != null ? Math.max(0D, raw.getDenseWeight()) : defaults.getDenseWeight();
        double sparseWeight = raw.getSparseWeight() != null ? Math.max(0D, raw.getSparseWeight()) : defaults.getSparseWeight();
        double weightSum = denseWeight + sparseWeight;
        if (weightSum <= 0D) {
            denseWeight = defaults.getDenseWeight();
            sparseWeight = defaults.getSparseWeight();
            weightSum = denseWeight + sparseWeight;
        }

        return RetrievalConfig.builder()
                .preset(preset)
                .hybridEnabled(raw.getHybridEnabled() != null ? raw.getHybridEnabled() : defaults.getHybridEnabled())
                .rerankEnabled(raw.getRerankEnabled() != null ? raw.getRerankEnabled() : defaults.getRerankEnabled())
                .denseTopK(denseTopK)
                .sparseTopK(sparseTopK)
                .fusionTopK(fusionTopK)
                .finalTopK(finalTopK)
                .denseWeight(denseWeight / weightSum)
                .sparseWeight(sparseWeight / weightSum)
                .build();
    }

    public String hash(RetrievalConfig config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(toJson(config).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(normalize(config).hashCode());
        }
    }

    public int getPipelineVersion() {
        return properties.getPipelineVersion();
    }

    public double getDenseSimilarityThreshold() {
        return properties.getDenseSimilarityThreshold();
    }

    public int getRrfK() {
        return properties.getRrfK();
    }

    private RetrievalConfig presetDefaults(RetrievalPreset preset) {
        HybridRetrievalProperties.PresetProperties presetProperties = switch (preset) {
            case EXACT_LOOKUP -> properties.getExactLookup();
            case LONGFORM_SYNTHESIS -> properties.getLongformSynthesis();
            case GENERAL_QA -> properties.getGeneralQa();
        };

        return RetrievalConfig.builder()
                .preset(preset)
                .hybridEnabled(presetProperties.isHybridEnabled())
                .rerankEnabled(presetProperties.isRerankEnabled())
                .denseTopK(presetProperties.getDenseTopK())
                .sparseTopK(presetProperties.getSparseTopK())
                .fusionTopK(presetProperties.getFusionTopK())
                .finalTopK(presetProperties.getFinalTopK())
                .denseWeight(presetProperties.getDenseWeight())
                .sparseWeight(presetProperties.getSparseWeight())
                .build();
    }

    private int positiveOrDefault(Integer candidate, Integer fallback) {
        return candidate != null && candidate > 0 ? candidate : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
