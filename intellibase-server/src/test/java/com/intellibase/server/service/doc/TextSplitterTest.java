package com.intellibase.server.service.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.dto.ChunkStrategy;
import com.intellibase.server.domain.dto.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSplitterTest {

    private TextSplitter textSplitter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ChunkStrategyResolver resolver = new ChunkStrategyResolver(objectMapper);
        textSplitter = new TextSplitter(objectMapper, resolver);
    }

    @Test
    void split_EmptyText_ReturnsEmpty() {
        List<TextChunk> chunks = textSplitter.split("   ", ChunkStrategy.builder().size(200).overlap(20).build());
        assertTrue(chunks.isEmpty());
    }

    @Test
    void split_LongParagraph_UsesRecursiveFallbackAndRespectsChunkSize() {
        String text = "这是一个很长的段落。".repeat(30);
        ChunkStrategy strategy = ChunkStrategy.builder()
                .size(80)
                .overlap(12)
                .minSize(20)
                .normalizeWhitespace(true)
                .build();

        List<TextChunk> chunks = textSplitter.split(text, strategy);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getContent().length() <= 80));
    }

    @Test
    void split_NewSectionDoesNotCarryOverlapAcrossHeadings() throws Exception {
        String text = """
                # 第一章 总则

                第一章内容很长很长，需要被拆成多个片段。第一章内容很长很长，需要被拆成多个片段。第一章内容很长很长，需要被拆成多个片段。

                # 第二章 适用范围

                第二章的内容从这里开始，不应该继承上一章尾部的 overlap。
                """;
        ChunkStrategy strategy = ChunkStrategy.builder()
                .size(70)
                .overlap(15)
                .minSize(20)
                .normalizeWhitespace(true)
                .build();

        List<TextChunk> chunks = textSplitter.split(text, strategy);

        TextChunk secondSectionChunk = chunks.stream()
                .filter(chunk -> chunk.getContent().contains("第二章 适用范围"))
                .findFirst()
                .orElseThrow();

        JsonNode metadata = objectMapper.readTree(secondSectionChunk.getMetadata());
        assertEquals("第二章 适用范围", metadata.get("sectionTitle").asText());
        assertFalse(metadata.get("overlapApplied").asBoolean());
    }

    @Test
    void split_CodeFenceIsPreservedAsIndependentBlock() throws Exception {
        String text = """
                # API 示例

                ```java
                System.out.println("hello");
                ```
                """;
        ChunkStrategy strategy = ChunkStrategy.builder()
                .size(120)
                .overlap(0)
                .minSize(20)
                .normalizeWhitespace(true)
                .build();

        List<TextChunk> chunks = textSplitter.split(text, strategy);

        TextChunk codeChunk = chunks.stream()
                .filter(chunk -> chunk.getContent().contains("System.out.println"))
                .findFirst()
                .orElseThrow();

        JsonNode metadata = objectMapper.readTree(codeChunk.getMetadata());
        assertEquals("CODE", metadata.get("blockType").asText());
    }

    @Test
    void split_OrderedListIsRecognizedAsListItem() throws Exception {
        String text = """
                使用步骤：

                1. 下载文档
                2. 清洗文本
                3. 入库检索
                """;
        ChunkStrategy strategy = ChunkStrategy.builder()
                .size(120)
                .overlap(0)
                .minSize(10)
                .normalizeWhitespace(true)
                .build();

        List<TextChunk> chunks = textSplitter.split(text, strategy);

        TextChunk listChunk = chunks.stream()
                .filter(chunk -> chunk.getContent().contains("1. 下载文档"))
                .findFirst()
                .orElseThrow();

        JsonNode metadata = objectMapper.readTree(listChunk.getMetadata());
        assertEquals("LIST_ITEM", metadata.get("blockType").asText());
    }

    @Test
    void split_OverlapDoesNotTrimCurrentBlockTail() throws Exception {
        String firstParagraph = "AAAAAAAAAABBBBBBBBBBCCCCCC";
        String secondParagraph = "DDDDDDDDDDEEEEEEEEEEFFFFF";
        String text = """
                # 章节一

                %s

                %s
                """.formatted(firstParagraph, secondParagraph);
        ChunkStrategy strategy = ChunkStrategy.builder()
                .size(30)
                .overlap(10)
                .minSize(10)
                .normalizeWhitespace(true)
                .build();

        List<TextChunk> chunks = textSplitter.split(text, strategy);

        TextChunk secondChunk = chunks.stream()
                .filter(chunk -> chunk.getContent().contains(secondParagraph))
                .findFirst()
                .orElseThrow();

        assertTrue(secondChunk.getContent().endsWith(secondParagraph));
        assertTrue(secondChunk.getContent().length() <= 30);

        JsonNode metadata = objectMapper.readTree(secondChunk.getMetadata());
        assertTrue(metadata.get("overlapApplied").asBoolean());
    }
}
