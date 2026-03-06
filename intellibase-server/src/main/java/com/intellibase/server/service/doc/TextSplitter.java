package com.intellibase.server.service.doc;

import com.intellibase.server.domain.dto.TextChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符文本分块器 (RecursiveCharacterTextSplitter)
 * <p>
 * 核心思想：按优先级依次尝试不同的分隔符来切分文本，保证每块尽量是完整的语义单元。
 * 分隔符优先级：段落(\n\n) > 换行(\n) > 句号/问号/感叹号 > 空格 > 强制按字符截断
 */
@Slf4j
@Service
public class TextSplitter {

    /** 默认分隔符列表，按优先级从高到低排列 */
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "？", "！", ".", "?", "!", " ", ""};

    /**
     * 将长文本分成多个块
     *
     * @param text         原始文本
     * @param chunkSize    每块的最大字符数
     * @param chunkOverlap 相邻块之间的重叠字符数（保证语义连贯）
     * @return 分块列表
     */
    public List<TextChunk> split(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> pieces = recursiveSplit(text, chunkSize, 0);

        // 合并过短的片段、应用 overlap
        List<String> merged = mergeWithOverlap(pieces, chunkSize, chunkOverlap);

        // 转为 TextChunk
        List<TextChunk> chunks = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            String content = merged.get(i).trim();
            if (content.isEmpty()) {
                continue;
            }
            chunks.add(TextChunk.builder()
                    .index(i)
                    .content(content)
                    .tokenCount(estimateTokens(content))
                    .build());
        }

        log.info("文本分块完成: 原文长度={}, chunkSize={}, overlap={}, 分块数={}",
                text.length(), chunkSize, chunkOverlap, chunks.size());
        return chunks;
    }

    /**
     * 递归切分：尝试用当前优先级的分隔符切分，如果某段仍然过长，则用下一级分隔符继续切
     */
    private List<String> recursiveSplit(String text, int chunkSize, int separatorIndex) {
        // 文本已经足够短，直接返回
        if (text.length() <= chunkSize) {
            List<String> result = new ArrayList<>();
            result.add(text);
            return result;
        }

        // 所有分隔符都试过了，强制按字符截断
        if (separatorIndex >= SEPARATORS.length) {
            return forceChunk(text, chunkSize);
        }

        String separator = SEPARATORS[separatorIndex];
        String[] parts;

        if (separator.isEmpty()) {
            // 空字符串分隔符 = 逐字符切分，等价于强制截断
            return forceChunk(text, chunkSize);
        }

        parts = text.split(quoteSeparator(separator), -1);

        // 如果该分隔符无法有效切分（只产生了1段），尝试下一个分隔符
        if (parts.length <= 1) {
            return recursiveSplit(text, chunkSize, separatorIndex + 1);
        }

        // 将切分结果重新组合，每段加回分隔符（除了最后一段）
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            // 把分隔符加回到当前段尾部（保留原始格式）
            String piece = (i < parts.length - 1) ? part + separator : part;

            if (current.length() + piece.length() <= chunkSize) {
                current.append(piece);
            } else {
                // 当前累积的内容已经够长，保存它
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }

                // 如果单个 piece 仍然超过 chunkSize，递归用下一级分隔符继续切
                if (piece.length() > chunkSize) {
                    result.addAll(recursiveSplit(piece, chunkSize, separatorIndex + 1));
                } else {
                    current.append(piece);
                }
            }
        }

        // 别忘了最后剩余的内容
        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    /**
     * 强制按字符数截断
     */
    private List<String> forceChunk(String text, int chunkSize) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            result.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return result;
    }

    /**
     * 合并过短的片段，并在相邻块之间添加重叠内容
     */
    private List<String> mergeWithOverlap(List<String> pieces, int chunkSize, int chunkOverlap) {
        if (pieces.isEmpty()) {
            return pieces;
        }
        if (chunkOverlap <= 0) {
            return pieces;
        }

        List<String> result = new ArrayList<>();
        result.add(pieces.get(0));

        for (int i = 1; i < pieces.size(); i++) {
            String prev = pieces.get(i - 1);
            String curr = pieces.get(i);

            // 从上一块的末尾取 overlap 长度的文本，拼到当前块头部
            int overlapLen = Math.min(chunkOverlap, prev.length());
            String overlap = prev.substring(prev.length() - overlapLen);
            String merged = overlap + curr;

            // 如果合并后超长，截断到 chunkSize
            if (merged.length() > chunkSize) {
                merged = merged.substring(0, chunkSize);
            }

            result.add(merged);
        }

        return result;
    }

    /**
     * 转义正则特殊字符
     */
    private String quoteSeparator(String sep) {
        return java.util.regex.Pattern.quote(sep);
    }

    /**
     * 粗略估算 Token 数（英文约 1 token ≈ 4 字符，中文约 1 token ≈ 2 字符）
     * 这里用混合估算：字符数 / 3
     */
    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 3);
    }

}
