package com.intellibase.server.service.doc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.dto.ChunkStrategy;
import com.intellibase.server.domain.dto.TextChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知文本分块器。
 * <p>
 * 先识别标题/列表/代码块/段落，再对超长块做递归降级切分，
 * 最后只在同一 section 内应用 overlap，避免跨章节污染。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextSplitter {

    private static final String BLOCK_TYPE_HEADING = "HEADING";
    private static final String BLOCK_TYPE_PARAGRAPH = "PARAGRAPH";
    private static final String BLOCK_TYPE_LIST_ITEM = "LIST_ITEM";
    private static final String BLOCK_TYPE_CODE = "CODE";
    private static final String[] RECURSIVE_SEPARATORS = {
            "\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", ";", "，", ",", " "
    };
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CHINESE_HEADING = Pattern.compile("^第[一二三四五六七八九十百零〇0-9]+([章节部分篇]).*$");
    private static final Pattern NUMERIC_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})[、.．\\s]+(.+)$");
    private static final Pattern CHINESE_SECTION_HEADING = Pattern.compile("^([一二三四五六七八九十]+)[、.．]\\s*(.+)$");
    private static final Pattern BULLET_LIST = Pattern.compile("^[-*•]\\s+.+$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+[.)、]\\s+.+$");

    private final ObjectMapper objectMapper;
    private final ChunkStrategyResolver chunkStrategyResolver;

    /**
     * 将长文本分成多个块
     *
     * @param text         原始文本
     * @param chunkSize    每块的最大字符数
     * @param chunkOverlap 相邻块之间的重叠字符数（保证语义连贯）
     * @return 分块列表
     */
    public List<TextChunk> split(String text, int chunkSize, int chunkOverlap) {
        ChunkStrategy strategy = chunkStrategyResolver.resolve(null, chunkSize, chunkOverlap);
        return split(text, strategy);
    }

    public List<TextChunk> split(String text, ChunkStrategy rawStrategy) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        ChunkStrategy strategy = chunkStrategyResolver.normalize(rawStrategy);
        if (Boolean.TRUE.equals(strategy.getParentChildEnabled())) {
            return splitParentChild(text, strategy);
        }
        return splitStandard(text, strategy);
    }

    private List<TextChunk> splitParentChild(String text, ChunkStrategy strategy) {
        ChunkStrategy parentStrategy = ChunkStrategy.builder()
                .version(strategy.getVersion())
                .type(strategy.getType())
                .size(strategy.getParentSize())
                .overlap(Math.min(strategy.getOverlap(), Math.max(0, strategy.getParentSize() - 1)))
                .minSize(Math.min(strategy.getMinSize(), strategy.getParentSize()))
                .normalizeWhitespace(strategy.getNormalizeWhitespace())
                .parentChildEnabled(false)
                .build();
        ChunkStrategy childStrategy = ChunkStrategy.builder()
                .version(strategy.getVersion())
                .type(strategy.getType())
                .size(strategy.getChildSize())
                .overlap(strategy.getChildOverlap())
                .minSize(Math.min(strategy.getMinSize(), strategy.getChildSize()))
                .normalizeWhitespace(strategy.getNormalizeWhitespace())
                .parentChildEnabled(false)
                .build();

        List<TextChunk> parents = splitStandard(text, parentStrategy);
        List<TextChunk> children = new ArrayList<>();
        int childIndex = 0;
        for (TextChunk parent : parents) {
            List<TextChunk> parentChildren = splitStandard(parent.getContent(), childStrategy);
            for (TextChunk child : parentChildren) {
                children.add(TextChunk.builder()
                        .index(childIndex++)
                        .content(child.getContent())
                        .tokenCount(child.getTokenCount())
                        .metadata(buildParentChildMetadata(strategy, parent, child))
                        .build());
            }
        }
        log.info("父子分块完成: 原文长度={}, parentSize={}, childSize={}, 父块数={}, 子块数={}",
                text.length(), strategy.getParentSize(), strategy.getChildSize(), parents.size(), children.size());
        return children;
    }

    private List<TextChunk> splitStandard(String text, ChunkStrategy strategy) {
        String normalized = normalizeText(text, strategy.getNormalizeWhitespace());
        if (normalized.isBlank()) {
            return List.of();
        }

        List<StructuredBlock> structuredBlocks = parseStructuredBlocks(normalized);
        List<StructuredBlock> mergedBlocks = mergeSmallBlocks(structuredBlocks, strategy);
        List<StructuredBlock> sizedBlocks = splitOversizedBlocks(mergedBlocks, strategy);
        List<StructuredBlock> finalBlocks = applyOverlapAndDedupe(sizedBlocks, strategy);

        List<TextChunk> chunks = new ArrayList<>(finalBlocks.size());
        int chunkIndex = 0;
        for (StructuredBlock block : finalBlocks) {
            String content = block.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            chunks.add(TextChunk.builder()
                    .index(chunkIndex++)
                    .content(content)
                    .tokenCount(estimateTokens(content))
                    .metadata(buildMetadata(block, strategy))
                    .build());
        }

        log.info("文本分块完成: 原文长度={}, chunkSize={}, overlap={}, minSize={}, 分块数={}",
                text.length(), strategy.getSize(), strategy.getOverlap(), strategy.getMinSize(), chunks.size());
        return chunks;
    }

    private String normalizeText(String text, boolean normalizeWhitespace) {
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ');
        if (!normalizeWhitespace) {
            return normalized.trim();
        }

        String[] rawLines = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        boolean inFence = false;
        boolean pendingBlankLine = false;

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            boolean fenceLine = isFence(trimmed);

            if (fenceLine) {
                if (pendingBlankLine && !lines.isEmpty()) {
                    lines.add("");
                    pendingBlankLine = false;
                }
                lines.add(trimmed);
                inFence = !inFence;
                continue;
            }

            if (trimmed.isEmpty()) {
                if (!lines.isEmpty()) {
                    pendingBlankLine = true;
                }
                continue;
            }

            if (pendingBlankLine && !lines.isEmpty()) {
                lines.add("");
                pendingBlankLine = false;
            }

            lines.add(inFence ? rawLine.stripTrailing() : collapseWhitespace(rawLine));
        }

        return String.join("\n", lines).trim();
    }

    private List<StructuredBlock> parseStructuredBlocks(String text) {
        List<StructuredBlock> result = new ArrayList<>();
        List<LineInfo> lines = toLineInfos(text);
        List<String> sectionStack = new ArrayList<>();

        int index = 0;
        while (index < lines.size()) {
            LineInfo line = lines.get(index);
            String trimmed = line.text().trim();
            if (trimmed.isEmpty()) {
                index++;
                continue;
            }

            if (isFence(trimmed)) {
                int startIndex = index;
                StringBuilder content = new StringBuilder(line.text());
                index++;
                while (index < lines.size()) {
                    LineInfo next = lines.get(index);
                    content.append('\n').append(next.text());
                    if (isFence(next.text().trim())) {
                        index++;
                        break;
                    }
                    index++;
                }
                LineInfo endLine = lines.get(Math.max(startIndex, index - 1));
                result.add(new StructuredBlock(
                        content.toString().trim(),
                        BLOCK_TYPE_CODE,
                        currentSectionTitle(sectionStack),
                        currentSectionPath(sectionStack),
                        line.start(),
                        endLine.end(),
                        false
                ));
                continue;
            }

            HeadingInfo headingInfo = parseHeading(trimmed);
            if (headingInfo != null) {
                updateSectionStack(sectionStack, headingInfo.level(), headingInfo.title());
                result.add(new StructuredBlock(
                        headingInfo.title(),
                        BLOCK_TYPE_HEADING,
                        currentSectionTitle(sectionStack),
                        currentSectionPath(sectionStack),
                        line.start(),
                        line.end(),
                        false
                ));
                index++;
                continue;
            }

            if (isListItem(trimmed)) {
                int startIndex = index;
                List<String> content = new ArrayList<>();
                content.add(trimmed);
                index++;
                while (index < lines.size()) {
                    String next = lines.get(index).text().trim();
                    if (next.isEmpty() || isFence(next) || parseHeading(next) != null || isListItem(next)) {
                        break;
                    }
                    content.add(next);
                    index++;
                }
                LineInfo endLine = lines.get(index - 1);
                result.add(new StructuredBlock(
                        String.join("\n", content),
                        BLOCK_TYPE_LIST_ITEM,
                        currentSectionTitle(sectionStack),
                        currentSectionPath(sectionStack),
                        lines.get(startIndex).start(),
                        endLine.end(),
                        false
                ));
                continue;
            }

            int startIndex = index;
            List<String> content = new ArrayList<>();
            while (index < lines.size()) {
                String next = lines.get(index).text().trim();
                if (next.isEmpty() || isFence(next) || parseHeading(next) != null || isListItem(next)) {
                    break;
                }
                content.add(next);
                index++;
            }
            LineInfo endLine = lines.get(index - 1);
            result.add(new StructuredBlock(
                    String.join("\n", content),
                    BLOCK_TYPE_PARAGRAPH,
                    currentSectionTitle(sectionStack),
                    currentSectionPath(sectionStack),
                    lines.get(startIndex).start(),
                    endLine.end(),
                    false
            ));
        }

        return result;
    }

    private List<StructuredBlock> mergeSmallBlocks(List<StructuredBlock> blocks, ChunkStrategy strategy) {
        if (blocks.isEmpty()) {
            return blocks;
        }

        List<StructuredBlock> merged = new ArrayList<>();
        StructuredBlock current = blocks.get(0);

        for (int i = 1; i < blocks.size(); i++) {
            StructuredBlock next = blocks.get(i);
            if (shouldMerge(current, next, strategy)) {
                current = mergeBlocks(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private boolean shouldMerge(StructuredBlock current, StructuredBlock next, ChunkStrategy strategy) {
        if (BLOCK_TYPE_HEADING.equals(next.blockType())) {
            return false;
        }
        if (BLOCK_TYPE_CODE.equals(current.blockType()) || BLOCK_TYPE_CODE.equals(next.blockType())) {
            return false;
        }
        if (BLOCK_TYPE_LIST_ITEM.equals(next.blockType()) && !BLOCK_TYPE_LIST_ITEM.equals(current.blockType())) {
            return false;
        }
        if (!Objects.equals(current.sectionPath(), next.sectionPath())) {
            return false;
        }

        int maxLength = current.content().length() + joinerForMerge(current, next).length() + next.content().length();
        if (maxLength > strategy.getSize()) {
            return false;
        }
        return current.content().length() < strategy.getMinSize() || BLOCK_TYPE_HEADING.equals(current.blockType());
    }

    private StructuredBlock mergeBlocks(StructuredBlock current, StructuredBlock next) {
        String content = current.content() + joinerForMerge(current, next) + next.content();
        String blockType = BLOCK_TYPE_HEADING.equals(current.blockType()) ? next.blockType() : current.blockType();
        return new StructuredBlock(
                content,
                blockType,
                next.sectionTitle() != null ? next.sectionTitle() : current.sectionTitle(),
                next.sectionPath() != null ? next.sectionPath() : current.sectionPath(),
                current.charStart(),
                next.charEnd(),
                false
        );
    }

    private String joinerForMerge(StructuredBlock current, StructuredBlock next) {
        return BLOCK_TYPE_HEADING.equals(current.blockType()) ? "\n" : "\n\n";
    }

    private List<StructuredBlock> splitOversizedBlocks(List<StructuredBlock> blocks, ChunkStrategy strategy) {
        List<StructuredBlock> result = new ArrayList<>();
        for (StructuredBlock block : blocks) {
            if (block.content().length() <= strategy.getSize()) {
                result.add(block);
                continue;
            }

            List<TextFragment> fragments = splitRecursively(
                    block.content(), block.charStart(), strategy.getSize(), 0);
            for (TextFragment fragment : fragments) {
                if (fragment.text().isBlank()) {
                    continue;
                }
                result.add(new StructuredBlock(
                        fragment.text().trim(),
                        block.blockType(),
                        block.sectionTitle(),
                        block.sectionPath(),
                        fragment.start(),
                        fragment.end(),
                        false
                ));
            }
        }
        return result;
    }

    private List<TextFragment> splitRecursively(String text, int baseStart, int chunkSize, int separatorIndex) {
        if (text.length() <= chunkSize) {
            return List.of(new TextFragment(text, baseStart, baseStart + text.length()));
        }

        if (separatorIndex >= RECURSIVE_SEPARATORS.length) {
            return forceChunk(text, baseStart, chunkSize);
        }

        String separator = RECURSIVE_SEPARATORS[separatorIndex];
        List<TextFragment> fragments = splitBySeparator(text, baseStart, separator);
        if (fragments.size() <= 1) {
            return splitRecursively(text, baseStart, chunkSize, separatorIndex + 1);
        }

        List<TextFragment> result = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        int currentStart = -1;
        int currentEnd = -1;

        for (TextFragment fragment : fragments) {
            if (currentText.length() == 0) {
                currentStart = fragment.start();
            }

            if (currentText.length() + fragment.text().length() <= chunkSize) {
                currentText.append(fragment.text());
                currentEnd = fragment.end();
                continue;
            }

            if (currentText.length() > 0) {
                result.add(new TextFragment(currentText.toString(), currentStart, currentEnd));
                currentText = new StringBuilder();
                currentStart = -1;
                currentEnd = -1;
            }

            if (fragment.text().length() > chunkSize) {
                result.addAll(splitRecursively(fragment.text(), fragment.start(), chunkSize, separatorIndex + 1));
            } else {
                currentText.append(fragment.text());
                currentStart = fragment.start();
                currentEnd = fragment.end();
            }
        }

        if (currentText.length() > 0) {
            result.add(new TextFragment(currentText.toString(), currentStart, currentEnd));
        }
        return result;
    }

    private List<TextFragment> splitBySeparator(String text, int baseStart, String separator) {
        List<TextFragment> result = new ArrayList<>();
        int cursor = 0;
        int index;

        while ((index = text.indexOf(separator, cursor)) >= 0) {
            int end = index + separator.length();
            result.add(new TextFragment(
                    text.substring(cursor, end),
                    baseStart + cursor,
                    baseStart + end
            ));
            cursor = end;
        }

        if (cursor < text.length()) {
            result.add(new TextFragment(
                    text.substring(cursor),
                    baseStart + cursor,
                    baseStart + text.length()
            ));
        }

        return result;
    }

    private List<TextFragment> forceChunk(String text, int baseStart, int chunkSize) {
        List<TextFragment> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            result.add(new TextFragment(
                    text.substring(i, end),
                    baseStart + i,
                    baseStart + end
            ));
        }
        return result;
    }

    private List<StructuredBlock> applyOverlapAndDedupe(List<StructuredBlock> blocks, ChunkStrategy strategy) {
        List<StructuredBlock> result = new ArrayList<>();
        StructuredBlock previous = null;

        for (StructuredBlock block : blocks) {
            StructuredBlock candidate = block;
            if (previous != null && shouldApplyOverlap(previous, block, strategy)) {
                String separator = needsSeparator(previous.content(), block.content()) ? "\n" : "";
                int availablePrefix = strategy.getSize() - block.content().length() - separator.length();
                int overlapLength = Math.min(strategy.getOverlap(),
                        Math.min(previous.content().length(), Math.max(0, availablePrefix)));
                if (overlapLength > 0) {
                    String overlap = previous.content().substring(previous.content().length() - overlapLength);
                    String merged = overlap + separator + block.content();
                    candidate = block.withContent(merged.trim(), block.charStart() - overlapLength, true);
                }
            }

            if (candidate.content().isBlank() || isNearDuplicate(result, candidate)) {
                continue;
            }

            result.add(candidate);
            previous = candidate;
        }

        return result;
    }

    private boolean shouldApplyOverlap(StructuredBlock previous, StructuredBlock current, ChunkStrategy strategy) {
        return strategy.getOverlap() > 0
                && Objects.equals(previous.sectionPath(), current.sectionPath())
                && !BLOCK_TYPE_HEADING.equals(previous.blockType())
                && !BLOCK_TYPE_HEADING.equals(current.blockType())
                && !BLOCK_TYPE_CODE.equals(previous.blockType())
                && !BLOCK_TYPE_CODE.equals(current.blockType());
    }

    private boolean isNearDuplicate(List<StructuredBlock> result, StructuredBlock candidate) {
        if (result.isEmpty()) {
            return false;
        }
        String previous = normalizeForCompare(result.get(result.size() - 1).content());
        String current = normalizeForCompare(candidate.content());
        return previous.equals(current);
    }

    private String normalizeForCompare(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private boolean needsSeparator(String prefix, String content) {
        if (prefix.isEmpty() || content.isEmpty()) {
            return false;
        }
        char last = prefix.charAt(prefix.length() - 1);
        char first = content.charAt(0);
        return !Character.isWhitespace(last) && !Character.isWhitespace(first);
    }

    private List<LineInfo> toLineInfos(String text) {
        String[] lines = text.split("\n", -1);
        List<LineInfo> result = new ArrayList<>(lines.length);
        int cursor = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            result.add(new LineInfo(line, cursor, cursor + line.length()));
            cursor += line.length();
            if (i < lines.length - 1) {
                cursor += 1;
            }
        }
        return result;
    }

    private HeadingInfo parseHeading(String line) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return new HeadingInfo(markdown.group(1).length(), markdown.group(2).trim());
        }

        Matcher chinese = CHINESE_HEADING.matcher(line);
        if (chinese.matches()) {
            String unit = chinese.group(1);
            int level = "节".equals(unit) ? 2 : 1;
            return new HeadingInfo(level, line.trim());
        }

        Matcher numeric = NUMERIC_HEADING.matcher(line);
        if (numeric.matches()) {
            String numbering = numeric.group(1);
            if (numbering.contains(".")) {
                return new HeadingInfo(numbering.split("\\.").length, line.trim());
            }
        }

        Matcher chineseSection = CHINESE_SECTION_HEADING.matcher(line);
        if (chineseSection.matches() && line.length() <= 32) {
            return new HeadingInfo(2, line.trim());
        }

        return null;
    }

    private boolean isListItem(String line) {
        return BULLET_LIST.matcher(line).matches() || ORDERED_LIST.matcher(line).matches();
    }

    private boolean isFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private void updateSectionStack(List<String> sectionStack, int level, String title) {
        int safeLevel = Math.max(1, level);
        while (sectionStack.size() >= safeLevel) {
            sectionStack.remove(sectionStack.size() - 1);
        }
        sectionStack.add(title);
    }

    private String currentSectionTitle(List<String> sectionStack) {
        return sectionStack.isEmpty() ? null : sectionStack.get(sectionStack.size() - 1);
    }

    private String currentSectionPath(List<String> sectionStack) {
        return sectionStack.isEmpty() ? null : String.join(" > ", sectionStack);
    }

    private String collapseWhitespace(String line) {
        return line.trim().replaceAll("[\\t\\x0B\\f ]+", " ");
    }

    private String buildMetadata(StructuredBlock block, ChunkStrategy strategy) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategyVersion", strategy.getVersion());
        metadata.put("blockType", block.blockType());
        if (block.sectionTitle() != null) {
            metadata.put("sectionTitle", block.sectionTitle());
        }
        if (block.sectionPath() != null) {
            metadata.put("sectionPath", block.sectionPath());
        }
        metadata.put("charStart", block.charStart());
        metadata.put("charEnd", block.charEnd());
        metadata.put("overlapApplied", block.overlapApplied());

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构建分块元数据失败", e);
        }
    }

    private String buildParentChildMetadata(ChunkStrategy strategy, TextChunk parent, TextChunk child) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategyVersion", strategy.getVersion());
        metadata.put("chunkingMode", "PARENT_CHILD");
        metadata.put("parentIndex", parent.getIndex());
        metadata.put("childIndexInParent", child.getIndex());
        metadata.put("parentSize", strategy.getParentSize());
        metadata.put("childSize", strategy.getChildSize());
        metadata.put("childOverlap", strategy.getChildOverlap());
        metadata.put("parentContent", parent.getContent());
        if (child.getMetadata() != null && !child.getMetadata().isBlank()) {
            metadata.put("childMetadata", child.getMetadata());
        }
        if (parent.getMetadata() != null && !parent.getMetadata().isBlank()) {
            metadata.put("parentMetadata", parent.getMetadata());
        }

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构建父子分块元数据失败", e);
        }
    }

    /**
     * 粗略估算 Token 数（英文约 1 token ≈ 4 字符，中文约 1 token ≈ 2 字符）
     * 这里用混合估算：字符数 / 3
     */
    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 3);
    }

    private record LineInfo(String text, int start, int end) {
    }

    private record TextFragment(String text, int start, int end) {
    }

    private record HeadingInfo(int level, String title) {
    }

    private record StructuredBlock(
            String content,
            String blockType,
            String sectionTitle,
            String sectionPath,
            int charStart,
            int charEnd,
            boolean overlapApplied
    ) {
        StructuredBlock withContent(String newContent, int newCharStart, boolean newOverlapApplied) {
            return new StructuredBlock(
                    newContent,
                    blockType,
                    sectionTitle,
                    sectionPath,
                    Math.max(0, newCharStart),
                    charEnd,
                    newOverlapApplied
            );
        }
    }
}
