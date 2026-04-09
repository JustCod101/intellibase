package com.intellibase.server.service.rag;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用层预分词器，优先保留技术标识并补充中文 bigram。
 */
@Service
public class LexicalTokenizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?iu)[a-z0-9]+(?:[._:/-][a-z0-9]+)+|[a-z]+[a-z0-9_]*|\\d+|[\\p{IsHan}]+");

    public String buildLexicalContent(String text) {
        return String.join(" ", tokenize(text, false));
    }

    public String buildLexicalQuery(String text) {
        return String.join(" ", tokenize(text, true));
    }

    public List<String> tokenizeForMatch(String text) {
        return tokenize(text, true);
    }

    public Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokenize(text, true));
    }

    private List<String> tokenize(String text, boolean dedupe) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        Matcher matcher = TOKEN_PATTERN.matcher(text);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (containsHan(raw)) {
                tokens.addAll(expandChinese(raw));
            } else {
                String normalized = normalizeTechnicalToken(raw);
                if (!normalized.isBlank()) {
                    tokens.add(normalized);
                }
            }
        }

        if (!dedupe) {
            return tokens;
        }
        return new ArrayList<>(new LinkedHashSet<>(tokens));
    }

    private List<String> expandChinese(String raw) {
        String compact = raw.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        tokens.add(compact);
        if (compact.length() <= 2) {
            return tokens;
        }

        for (int i = 0; i < compact.length() - 1; i++) {
            tokens.add(compact.substring(i, i + 2));
        }
        return tokens;
    }

    private String normalizeTechnicalToken(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[.\\-/:]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized;
    }

    private boolean containsHan(String token) {
        return token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
