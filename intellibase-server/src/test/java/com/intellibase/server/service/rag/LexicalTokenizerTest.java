package com.intellibase.server.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalTokenizerTest {

    private final LexicalTokenizer lexicalTokenizer = new LexicalTokenizer();

    @Test
    @DisplayName("预分词 - 保留技术标识并补充中文 bigram")
    void buildLexicalQuery_PreservesTechnicalTokens() {
        List<String> tokens = lexicalTokenizer.tokenizeForMatch("请处理 HTTP_409，调用 order.create，版本 v2.3.1");

        assertTrue(tokens.contains("http_409"));
        assertTrue(tokens.contains("order_create"));
        assertTrue(tokens.contains("v2_3_1"));
        assertTrue(tokens.contains("请处理"));
        assertTrue(tokens.contains("处理"));
    }
}
