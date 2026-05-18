package com.intellibase.server.domain.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 兼容两种 chunkStrategy 输入格式：
 * 1. 标准对象：{"size":800,"overlap":120,...}
 * 2. 历史字符串："{"size":512,"overlap":64}"
 */
public class ChunkStrategyDeserializer extends JsonDeserializer<ChunkStrategy> {

    @Override
    public ChunkStrategy deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectCodec codec = parser.getCodec();
        JsonNode node = codec.readTree(parser);
        return fromNode(node, codec);
    }

    private ChunkStrategy fromNode(JsonNode node, ObjectCodec codec) throws IOException {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isTextual()) {
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            ObjectMapper mapper = codec instanceof ObjectMapper objectMapper
                    ? objectMapper
                    : new ObjectMapper();
            JsonNode parsed = mapper.readTree(raw);
            return fromNode(parsed, codec);
        }

        if (!node.isObject()) {
            return null;
        }

        ChunkStrategy strategy = new ChunkStrategy();
        if (node.hasNonNull("version")) {
            strategy.setVersion(node.get("version").asInt());
        }
        if (node.hasNonNull("type")) {
            strategy.setType(node.get("type").asText());
        }
        if (node.hasNonNull("size")) {
            strategy.setSize(node.get("size").asInt());
        }
        if (node.hasNonNull("overlap")) {
            strategy.setOverlap(node.get("overlap").asInt());
        }
        if (node.hasNonNull("minSize")) {
            strategy.setMinSize(node.get("minSize").asInt());
        }
        if (node.has("normalizeWhitespace") && !node.get("normalizeWhitespace").isNull()) {
            strategy.setNormalizeWhitespace(node.get("normalizeWhitespace").asBoolean());
        }
        if (node.has("parentChildEnabled") && !node.get("parentChildEnabled").isNull()) {
            strategy.setParentChildEnabled(node.get("parentChildEnabled").asBoolean());
        }
        if (node.hasNonNull("parentSize")) {
            strategy.setParentSize(node.get("parentSize").asInt());
        }
        if (node.hasNonNull("childSize")) {
            strategy.setChildSize(node.get("childSize").asInt());
        }
        if (node.hasNonNull("childOverlap")) {
            strategy.setChildOverlap(node.get("childOverlap").asInt());
        }
        return strategy;
    }
}
