package com.intellibase.server.service.rag;

import com.intellibase.server.domain.vo.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 构建器 (PromptBuilder) 单元测试
 */
class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("Prompt构建 - 包含多个上下文片段")
    void buildSystemPrompt_WithContexts() {
        List<RetrievalResult> contexts = List.of(
                RetrievalResult.builder().content("分块1内容").build(),
                RetrievalResult.builder().content("分块2内容").build()
        );

        String prompt = promptBuilder.buildSystemPrompt(contexts);

        assertTrue(prompt.contains("你是一个专业的企业知识助手"));
        assertTrue(prompt.contains("[片段1] 分块1内容"));
        assertTrue(prompt.contains("[片段2] 分块2内容"));
    }

    @Test
    @DisplayName("Prompt构建 - 无上下文时显示默认提示")
    void buildSystemPrompt_EmptyContexts() {
        String prompt = promptBuilder.buildSystemPrompt(List.of());

        assertTrue(prompt.contains("（未找到相关文档片段）"));
    }
}
