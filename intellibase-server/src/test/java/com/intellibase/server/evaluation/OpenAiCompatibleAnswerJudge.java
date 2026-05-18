package com.intellibase.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional OpenAI-compatible LLM-as-judge for RAGAS-style answer evaluation.
 * It is kept in test scope so production RAG remains a single Java backend and
 * no Python evaluator service is introduced.
 */
public class OpenAiCompatibleAnswerJudge implements AnswerJudge {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public OpenAiCompatibleAnswerJudge(ObjectMapper objectMapper,
                                       String apiKey,
                                       String baseUrl,
                                       String model) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiKey = apiKey;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.model = model;
    }

    @Override
    public JudgeScore judge(GoldenQaCase goldenCase, RetrievalRunRecord runRecord) {
        try {
            String content = callJudge(goldenCase, runRecord);
            Map<String, Object> parsed = objectMapper.readValue(extractJson(content), new TypeReference<>() {});
            double faithfulness = asDouble(parsed.get("faithfulness"));
            double answerRelevance = asDouble(parsed.get("answer_relevance"));
            String reason = String.valueOf(parsed.getOrDefault("reason", "llm judge"));
            return new JudgeScore(goldenCase.id(), clamp(faithfulness), clamp(answerRelevance), reason);
        } catch (Exception e) {
            return new HeuristicAnswerJudge().judge(goldenCase, runRecord);
        }
    }

    private String callJudge(GoldenQaCase goldenCase, RetrievalRunRecord runRecord) throws IOException, InterruptedException {
        String contexts = String.join("\n---\n", runRecord.candidates().stream()
                .map(RetrievedCandidate::content)
                .filter(content -> content != null && !content.isBlank())
                .toList());
        String userPrompt = "请作为 RAGAS 风格评测裁判，只输出 JSON。"
                + "faithfulness 表示答案是否被上下文支持，answer_relevance 表示答案是否回答问题，分数范围 0 到 1。\n"
                + "问题：" + goldenCase.question() + "\n"
                + "标准答案：" + goldenCase.referenceAnswer() + "\n"
                + "检索上下文：" + contexts + "\n"
                + "待评答案：" + runRecord.generatedAnswer() + "\n"
                + "输出格式：{\"faithfulness\":0.0,\"answer_relevance\":0.0,\"reason\":\"...\"}";

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a strict RAG evaluation judge. Return JSON only."),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("LLM judge failed with status " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> responseBody = objectMapper.readValue(response.body(), new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content"));
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }
}
