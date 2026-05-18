package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.interceptor.TenantInterceptor;
import com.intellibase.server.service.rag.EmbeddingService;
import com.intellibase.server.service.rag.ExternalRerankService;
import com.intellibase.server.service.rag.LexicalTokenizer;
import com.intellibase.server.service.rag.QueryRewriteService;
import com.intellibase.server.service.rag.RetrievalCacheService;
import com.intellibase.server.service.rag.RetrievalService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real API retrieval evaluation harness.
 * <p>
 * Disabled by default because it calls the configured embedding API and, optionally, LLM
 * query rewrite / external rerank APIs. This is the runner to use for real quality numbers;
 * seeded deterministic runners are only regression harnesses.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@EnabledIfSystemProperty(named = "evaluation.real-api.enabled", matches = "true")
class RealApiRetrievalEvaluationIT {

    private static final long REAL_API_KB_ID = 95_001L;
    private static final long REAL_API_DOC_ID = 95_001L;
    private static final long REAL_API_CHUNK_ID_BASE = 9_500_000L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper jsonlMapper = new ObjectMapper();
    private final RetrievalMetricCalculator calculator = new RetrievalMetricCalculator();
    private final AnswerJudge answerJudge = AnswerJudgeFactory.create(objectMapper);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private RetrievalCacheService retrievalCacheService;

    @Autowired
    private LexicalTokenizer lexicalTokenizer;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private ExternalRerankService externalRerankService;

    @Test
    @DisplayName("真实 API 评测 - real embedding / optional rewrite / optional external rerank")
    void compareRetrievalVersionsWithRealApis() throws Exception {
        Assumptions.assumeTrue(hasRealEmbeddingApiKey(),
                "Set OPENAI_API_KEY (or embedding.api-key) before running real API evaluation");

        TenantInterceptor.TenantContext.set(1L);
        try {
            compareRetrievalVersionsWithRealApisInternal();
        } finally {
            TenantInterceptor.TenantContext.clear();
        }
    }

    private void compareRetrievalVersionsWithRealApisInternal() throws Exception {
        List<GoldenQaCase> goldenCases = EvaluationFixtures.loadGoldenCases(objectMapper, "/evaluation/golden_qa.jsonl");
        seedRealEmbeddingGoldenCorpus(goldenCases);

        List<RealApiScenario> scenarios = new ArrayList<>();
        scenarios.add(new RealApiScenario("baseline-dense-only", denseOnlyConfig(), false, GoldenQaCase::question));
        scenarios.add(new RealApiScenario("hybrid-rrf", hybridConfig(false), false, GoldenQaCase::question));
        scenarios.add(new RealApiScenario("hybrid-local-rerank", hybridConfig(true), false, GoldenQaCase::question));
        if (externalRerankConfigured()) {
            scenarios.add(new RealApiScenario("hybrid-external-rerank", hybridConfig(true), true, GoldenQaCase::question));
        }
        if (queryRewriteConfigured()) {
            scenarios.add(new RealApiScenario("hybrid-rerank-query-rewrite", hybridConfig(true), externalRerankConfigured(),
                    golden -> queryRewriteService.rewrite(golden.question()).retrievalText()));
        }

        Map<String, RealApiEvaluationResult> results = new LinkedHashMap<>();
        for (RealApiScenario scenario : scenarios) {
            applyRetrievalConfig(scenario.retrievalConfigJson());
            ReflectionTestUtils.setField(externalRerankService, "externalEnabled", scenario.externalRerankEnabled());
            retrievalCacheService.invalidateByKbId(REAL_API_KB_ID);
            List<RetrievalRunRecord> runRecords = runScenario(goldenCases, scenario);
            RetrievalMetrics metrics = calculator.calculate(goldenCases, runRecords, 5);
            List<JudgeScore> judgeScores = goldenCases.stream()
                    .map(golden -> answerJudge.judge(golden, runRecords.stream()
                            .filter(record -> record.questionId().equals(golden.id()))
                            .findFirst()
                            .orElse(new RetrievalRunRecord(golden.id(), List.of(), ""))))
                    .toList();
            results.put(scenario.name(), new RealApiEvaluationResult(metrics, judgeScores, runRecords));
        }

        writeReports(results);
        assertEquals(scenarios.size(), results.size());
    }

    private List<RetrievalRunRecord> runScenario(List<GoldenQaCase> goldenCases, RealApiScenario scenario) {
        List<RetrievalRunRecord> runRecords = new ArrayList<>();
        for (GoldenQaCase golden : goldenCases) {
            String query = scenario.queryProvider().apply(golden);
            float[] queryVector = embeddingService.embed(query);
            List<RetrievalResult> results = retrievalService.retrieve(queryVector, REAL_API_KB_ID, query);
            List<RetrievedCandidate> candidates = new ArrayList<>();
            for (int rank = 0; rank < results.size(); rank++) {
                RetrievalResult result = results.get(rank);
                candidates.add(new RetrievedCandidate(
                        evalChunkId(result.getChunkId()),
                        rank + 1,
                        result.getScore(),
                        result.getContent()
                ));
            }
            runRecords.add(new RetrievalRunRecord(
                    golden.id(),
                    candidates,
                    candidates.isEmpty() ? "" : candidates.get(0).content()
            ));
        }
        return runRecords;
    }

    private void seedRealEmbeddingGoldenCorpus(List<GoldenQaCase> goldenCases) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE kb_id = ?", REAL_API_KB_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", REAL_API_DOC_ID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", REAL_API_KB_ID);

        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, retrieval_config, doc_count, status)
                VALUES (?, 'real-api-golden-corpus', 'Golden QA corpus embedded through the configured real embedding API', 1,
                        ?, ?::jsonb, 1, 'ACTIVE')
                """, REAL_API_KB_ID, System.getProperty("embedding.model-name", System.getenv().getOrDefault("EMBEDDING_MODEL_NAME", "configured-real-embedding")),
                hybridConfig(true));
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count)
                VALUES (?, ?, 'golden-qa-real-api.jsonl', 'evaluation/golden_qa.jsonl', 'jsonl', 1,
                        'real-api-golden-corpus', 'COMPLETED', ?)
                """, REAL_API_DOC_ID, REAL_API_KB_ID, goldenCases.size());

        List<String> contents = goldenCases.stream()
                .map(golden -> golden.referenceAnswer() + "\n关键词：" + String.join("、", golden.expectedKeywords()))
                .toList();
        List<float[]> embeddings = embeddingService.embedBatch(contents);

        for (int i = 0; i < goldenCases.size(); i++) {
            GoldenQaCase golden = goldenCases.get(i);
            String content = contents.get(i);
            String lexicalContent = lexicalTokenizer.buildLexicalContent(
                    golden.question() + " " + content + " " + String.join(" ", golden.expectedKeywords()));
            String metadata = "{\"evalChunkId\":\"" + golden.relevantChunkIds().get(0)
                    + "\",\"source\":\"golden_qa_real_api\",\"embedding\":\"real_api\"}";
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (id, doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?), ?::jsonb)
                    """,
                    REAL_API_CHUNK_ID_BASE + i,
                    REAL_API_DOC_ID,
                    REAL_API_KB_ID,
                    i,
                    content,
                    lexicalContent,
                    Math.max(1, content.length() / 2),
                    EmbeddingService.toVectorString(embeddings.get(i)),
                    lexicalContent,
                    metadata);
        }
    }

    private void applyRetrievalConfig(String configJson) {
        jdbcTemplate.update("UPDATE knowledge_base SET retrieval_config = ?::jsonb WHERE id = ?", configJson, REAL_API_KB_ID);
    }

    private String evalChunkId(Long numericChunkId) {
        return jdbcTemplate.queryForObject(
                "SELECT metadata->>'evalChunkId' FROM document_chunk WHERE id = ?",
                String.class,
                numericChunkId
        );
    }

    private void writeReports(Map<String, RealApiEvaluationResult> results) throws IOException {
        Path outputDir = Path.of("target", "evaluation");
        Files.createDirectories(outputDir);
        Map<String, Object> json = new LinkedHashMap<>();
        StringBuilder markdown = new StringBuilder("# IntelliBase Real API Retrieval Evaluation\n\n")
                .append("This report is generated with the configured real embedding API")
                .append(queryRewriteConfigured() ? ", query rewrite API" : "")
                .append(externalRerankConfigured() ? ", and external rerank API" : "")
                .append(". Record API vendor/model/hardware/concurrency beside any published metric.\n\n")
                .append("| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");

        for (Map.Entry<String, RealApiEvaluationResult> entry : results.entrySet()) {
            RealApiEvaluationResult result = entry.getValue();
            double faithfulness = result.judgeScores().stream().mapToDouble(JudgeScore::faithfulness).average().orElse(0D);
            double answerRelevance = result.judgeScores().stream().mapToDouble(JudgeScore::answerRelevance).average().orElse(0D);
            markdown.append("| ").append(entry.getKey())
                    .append(" | ").append(pct(result.metrics().recallAtK()))
                    .append(" | ").append(pct(result.metrics().mrr()))
                    .append(" | ").append(pct(result.metrics().hitRateAtK()))
                    .append(" | ").append(pct(faithfulness))
                    .append(" | ").append(pct(answerRelevance))
                    .append(" |\n");
            json.put(entry.getKey(), Map.of(
                    "retrieval", result.metrics(),
                    "judge", result.judgeScores()
            ));
            Files.writeString(outputDir.resolve("real-api-" + entry.getKey() + "-run.jsonl"), result.runRecords().stream()
                    .map(record -> {
                        try {
                            return jsonlMapper.writeValueAsString(record);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
        }

        Files.writeString(outputDir.resolve("real-api-comparison-report.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("real-api-comparison-metrics.json"), objectMapper.writeValueAsString(json), StandardCharsets.UTF_8);
    }

    private String denseOnlyConfig() {
        return """
                {"preset":"GENERAL_QA","hybridEnabled":false,"rerankEnabled":false,
                 "denseTopK":5,"sparseTopK":0,"fusionTopK":5,"finalTopK":5,
                 "denseWeight":1.0,"sparseWeight":0.0}
                """;
    }

    private String hybridConfig(boolean rerankEnabled) {
        return """
                {"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":%s,
                 "denseTopK":20,"sparseTopK":20,"fusionTopK":20,"finalTopK":5,
                 "denseWeight":0.55,"sparseWeight":0.45}
                """.formatted(rerankEnabled);
    }

    private boolean hasRealEmbeddingApiKey() {
        String key = firstText(System.getProperty("embedding.api-key"), System.getenv("OPENAI_API_KEY"));
        return StringUtils.hasText(key) && !"test-openai-key".equals(key);
    }

    private boolean externalRerankConfigured() {
        return StringUtils.hasText(firstText(System.getProperty("rag.rerank.api-url"), System.getenv("RAG_RERANK_API_URL")))
                && StringUtils.hasText(firstText(System.getProperty("rag.rerank.api-key"), System.getenv("RAG_RERANK_API_KEY")));
    }

    private boolean queryRewriteConfigured() {
        return Boolean.getBoolean("rag.query-rewrite.enabled")
                || "true".equalsIgnoreCase(System.getenv("RAG_QUERY_REWRITE_ENABLED"));
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String pct(double value) {
        return String.format("%.2f%%", value * 100D);
    }

    private record RealApiScenario(String name,
                                   String retrievalConfigJson,
                                   boolean externalRerankEnabled,
                                   Function<GoldenQaCase, String> queryProvider) {
    }

    private record RealApiEvaluationResult(RetrievalMetrics metrics,
                                           List<JudgeScore> judgeScores,
                                           List<RetrievalRunRecord> runRecords) {
    }
}
