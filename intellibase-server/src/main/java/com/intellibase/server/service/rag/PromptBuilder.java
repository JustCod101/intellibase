package com.intellibase.server.service.rag;

import com.intellibase.server.domain.vo.RetrievalResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Prompt (提示词) 模板构建器
 * <p>
 * 职责：负责将“干巴巴”的检索内容按照固定的套路（Template）组装，
 * 引导大模型（LLM）基于上下文回答问题，避免产生“幻觉”（即一本正经地胡说八道）。
 */
@Service
public class PromptBuilder {

    // 系统角色的 Prompt：定义大模型的行为准则。
    private static final String SYSTEM_PROMPT = """
            你是一个专业的企业知识助手。请严格基于以下提供的上下文信息来回答用户的问题。

            要求：
            1. 只使用上下文中提供的信息来回答，不要编造或推测。
            2. 如果上下文中没有相关信息，请明确告知用户"根据现有知识库内容，暂未找到相关信息"。
            3. 回答要准确、简洁、有条理。
            4. 如果引用了具体内容，请标注来源片段编号。
            """;

    /**
     * 构建完整的 System Prompt（包含检索出的背景资料）
     * 
     * @param contexts 从数据库里检索出的相关文档片段
     * @return 返回一段包含背景知识的、给大模型的总指令
     */
    public String buildSystemPrompt(List<RetrievalResult> contexts) {
        // 如果没有检索到相关内容，告诉模型没有背景知识，它应该按规则返回“没找到”
        if (contexts == null || contexts.isEmpty()) {
            return SYSTEM_PROMPT + "\n\n【上下文】\n（未找到相关文档片段）";
        }

        // 将多个文档片段，按照 [片段1] 内容 [片段2] 内容 的格式拼接起来
        String contextBlock = IntStream.range(0, contexts.size())
                .mapToObj(i -> "[片段" + (i + 1) + "] " + contexts.get(i).getContent())
                .collect(Collectors.joining("\n\n"));

        // 拼接成最终发送给大模型的 System Message
        return SYSTEM_PROMPT + "\n\n【上下文】\n" + contextBlock;
    }

    /**
     * 构建用户消息
     * 目前阶段直接透传用户提问，未来可以在这里加入历史对话上下文。
     * 
     * @param question 用户的原始问题
     */
    public String buildUserMessage(String question) {
        return question;
    }

}
