package com.intellibase.server.service.doc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.dto.ChunkStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 统一处理 chunkStrategy 的默认值、兼容解析和归一化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkStrategyResolver {

    public static final int DEFAULT_VERSION = 2;
    public static final String DEFAULT_TYPE = "STRUCTURE_AWARE";
    public static final int DEFAULT_SIZE = 800;
    public static final int DEFAULT_OVERLAP = 120;
    public static final int DEFAULT_MIN_SIZE = 200;
    public static final boolean DEFAULT_NORMALIZE_WHITESPACE = true;

    private final ObjectMapper objectMapper;

    public ChunkStrategy defaultStrategy() {
        return ChunkStrategy.builder()
                .version(DEFAULT_VERSION)
                .type(DEFAULT_TYPE)
                .size(DEFAULT_SIZE)
                .overlap(DEFAULT_OVERLAP)
                .minSize(DEFAULT_MIN_SIZE)
                .normalizeWhitespace(DEFAULT_NORMALIZE_WHITESPACE)
                .build();
    }

    public ChunkStrategy fromJson(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return defaultStrategy();
        }
        try {
            return normalize(objectMapper.readValue(rawJson, ChunkStrategy.class));
        } catch (Exception e) {
            log.warn("解析 chunkStrategy 失败，回退默认策略: {}", rawJson, e);
            return defaultStrategy();
        }
    }

    public String toJson(ChunkStrategy strategy) {
        try {
            return objectMapper.writeValueAsString(normalize(strategy));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化 chunkStrategy 失败", e);
        }
    }

    public ChunkStrategy resolve(ChunkStrategy strategy, Integer legacyChunkSize, Integer legacyChunkOverlap) {
        if (strategy != null) {
            return normalize(strategy);
        }
        return normalize(ChunkStrategy.builder()
                .size(legacyChunkSize)
                .overlap(legacyChunkOverlap)
                .build());
    }

    public ChunkStrategy normalize(ChunkStrategy raw) {
        if (raw == null) {
            return defaultStrategy();
        }

        int size = raw.getSize() != null ? Math.max(100, raw.getSize()) : DEFAULT_SIZE;
        int overlapCandidate = raw.getOverlap() != null ? raw.getOverlap() : DEFAULT_OVERLAP;
        int overlap = clamp(overlapCandidate, 0, Math.max(0, size - 1));

        int suggestedMinSize = Math.min(DEFAULT_MIN_SIZE, Math.max(80, size / 4));
        int minSizeCandidate = raw.getMinSize() != null ? raw.getMinSize() : suggestedMinSize;
        int minSize = clamp(minSizeCandidate, 1, size);

        String type = StringUtils.hasText(raw.getType())
                ? raw.getType().trim().toUpperCase(Locale.ROOT)
                : DEFAULT_TYPE;
        int version = raw.getVersion() != null && raw.getVersion() > 0
                ? raw.getVersion()
                : DEFAULT_VERSION;
        boolean normalizeWhitespace = raw.getNormalizeWhitespace() == null
                ? DEFAULT_NORMALIZE_WHITESPACE
                : raw.getNormalizeWhitespace();

        return ChunkStrategy.builder()
                .version(version)
                .type(type)
                .size(size)
                .overlap(overlap)
                .minSize(minSize)
                .normalizeWhitespace(normalizeWhitespace)
                .build();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
