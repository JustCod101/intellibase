package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.vo.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二阶段外部 Rerank。兼容常见 rerank API 形态：
 * <ul>
 *   <li>OpenAI/Cohere/SiliconFlow-like: request {model, query, documents, top_n};
 *       response {results:[{index,relevance_score}]}</li>
 *   <li>DashScope qwen/gte rerank: request {model, input:{query,documents}, parameters:{top_n}};
 *       response {output:{results:[{index,relevance_score}]}}</li>
 * </ul>
 * 默认关闭，失败时回退本地 RRF/规则排序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalRerankService {

    private final ObjectMapper objectMapper;

    @Value("${rag.rerank.external-enabled:false}")
    private boolean externalEnabled;

    @Value("${rag.rerank.api-url:}")
    private String apiUrl;

    @Value("${rag.rerank.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.model:bge-reranker-v2-m3}")
    private String model;

    @Value("${rag.rerank.api-format:auto}")
    private String apiFormat;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isExternalEnabled() {
        return externalEnabled && apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!isExternalEnabled()) {
            return candidates.stream().limit(finalTopK).toList();
        }
        try {
            List<String> documents = candidates.stream().map(RetrievalResult::getContent).toList();
            int topN = Math.min(finalTopK, candidates.size());
            Map<String, Object> body = buildRequestBody(query, documents, topN);
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("rerank status=" + response.statusCode() + ": " + response.body());
            }
            return applyRerankResponse(candidates, response.body(), finalTopK);
        } catch (Exception e) {
            log.warn("外部 rerank 失败，回退本地排序: candidates={}, reason={}", candidates.size(), e.toString());
            log.debug("外部 rerank 失败详情", e);
            return candidates.stream().limit(finalTopK).toList();
        }
    }

    private Map<String, Object> buildRequestBody(String query, List<String> documents, int topN) {
        if (useDashScopeFormat()) {
            return Map.of(
                    "model", model,
                    "input", Map.of(
                            "query", query,
                            "documents", documents
                    ),
                    "parameters", Map.of(
                            "top_n", topN,
                            "return_documents", false
                    )
            );
        }
        return Map.of(
                "model", model,
                "query", query,
                "documents", documents,
                "top_n", topN
        );
    }

    private boolean useDashScopeFormat() {
        if ("dashscope".equalsIgnoreCase(apiFormat)) {
            return true;
        }
        if (!"auto".equalsIgnoreCase(apiFormat)) {
            return false;
        }
        String normalizedUrl = apiUrl == null ? "" : apiUrl.toLowerCase();
        String normalizedModel = model == null ? "" : model.toLowerCase();
        return normalizedUrl.contains("dashscope.aliyuncs.com")
                || normalizedModel.startsWith("qwen")
                || normalizedModel.startsWith("gte-rerank");
    }

    private List<RetrievalResult> applyRerankResponse(List<RetrievalResult> candidates, String responseBody, int finalTopK) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(responseBody, new TypeReference<>() {});
        List<Map<String, Object>> results = extractResults(parsed);
        Map<Integer, Double> scoreByIndex = new HashMap<>();
        for (Map<String, Object> result : results) {
            int index = ((Number) result.get("index")).intValue();
            Object rawScore = result.containsKey("relevance_score") ? result.get("relevance_score") : result.get("score");
            double score = rawScore instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(rawScore));
            scoreByIndex.put(index, score);
        }

        List<RetrievalResult> reranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalResult candidate = candidates.get(i);
            Double score = scoreByIndex.get(i);
            if (score != null) {
                candidate.setRerankScore(score);
                candidate.setScore(score);
                reranked.add(candidate);
            }
        }
        if (reranked.isEmpty()) {
            return candidates.stream().limit(finalTopK).toList();
        }
        return reranked.stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::getScore).reversed())
                .limit(finalTopK)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractResults(Map<String, Object> parsed) {
        Object results = parsed.get("results");
        if (results == null && parsed.get("output") instanceof Map<?, ?> output) {
            results = ((Map<String, Object>) output).get("results");
        }
        if (results == null) {
            results = parsed.get("data");
        }
        if (results instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }
}
