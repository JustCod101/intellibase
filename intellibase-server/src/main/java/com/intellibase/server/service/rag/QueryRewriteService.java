package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 轻量 Query Rewriting：将长句/口语化问题改写成检索友好查询。
 * 不引入 Agent/工具调用；失败时严格回退原问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final ObjectMapper objectMapper;

    @Value("${rag.query-rewrite.enabled:false}")
    private boolean enabled;

    @Value("${rag.query-rewrite.min-length:18}")
    private int minLength;

    @Value("${rag.query-rewrite.hyde-enabled:false}")
    private boolean hydeEnabled;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model-name}")
    private String modelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public RewrittenQuery rewrite(String question) {
        if (!enabled || !StringUtils.hasText(question) || question.length() < minLength) {
            return RewrittenQuery.original(question);
        }
        try {
            String rewritten = callRewrite(question);
            if (!StringUtils.hasText(rewritten)) {
                return RewrittenQuery.original(question);
            }
            String retrievalText = hydeEnabled ? callHyde(question, rewritten) : rewritten;
            return new RewrittenQuery(question, rewritten, StringUtils.hasText(retrievalText) ? retrievalText : rewritten, hydeEnabled);
        } catch (Exception e) {
            log.warn("Query rewrite 失败，回退原始问题: {}", question, e);
            return RewrittenQuery.original(question);
        }
    }

    private String callRewrite(String question) throws Exception {
        String prompt = "将用户问题改写为适合知识库检索的简洁查询，保留技术关键词、错误码、类名、参数名。只输出改写后的查询。\n用户问题：" + question;
        return chat(prompt);
    }

    private String callHyde(String question, String rewritten) throws Exception {
        String prompt = "为下面的检索查询生成一段可能出现在技术文档中的假设性答案，用于 HyDE 向量检索。不要编造具体数字。\n原问题："
                + question + "\n检索查询：" + rewritten;
        return chat(prompt);
    }

    private String chat(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", "You rewrite RAG search queries. No tools. Return plain text only."),
                        Map.of("role", "user", "content", prompt)
                )
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("rewrite status=" + response.statusCode());
        }
        Map<String, Object> responseBody = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content")).trim();
    }

    public record RewrittenQuery(String original, String rewritten, String retrievalText, boolean hydeUsed) {
        public static RewrittenQuery original(String question) {
            return new RewrittenQuery(question, question, question, false);
        }
    }
}
