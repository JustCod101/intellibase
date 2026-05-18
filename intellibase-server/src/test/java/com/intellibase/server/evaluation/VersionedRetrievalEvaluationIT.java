package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.interceptor.TenantInterceptor;
import com.intellibase.server.service.rag.RetrievalCacheService;
import com.intellibase.server.service.rag.RetrievalService;
import com.intellibase.server.service.rag.LexicalTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version matrix for retrieval evaluation.
 * <p>
 * This test intentionally stays disabled by default because it requires live PostgreSQL/pgvector.
 * It seeds an adversarial deterministic corpus where dense-only retrieval sees vector-near distractors
 * and sparse/hybrid retrieval can recover the relevant chunk. It proves the comparison harness and
 * report format; real semantic-quality claims still require real documents + real embedding vectors.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "rag.rerank.external-enabled=false"
})
@EnabledIfSystemProperty(named = "evaluation.versions.enabled", matches = "true")
class VersionedRetrievalEvaluationIT {

    private static final long VERSION_KB_ID = 93_001L;
    private static final long VERSION_DOC_ID = 93_001L;
    private static final long RELEVANT_CHUNK_ID_BASE = 9_300_000L;
    private static final long DISTRACTOR_CHUNK_ID_BASE = 9_400_000L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper jsonlMapper = new ObjectMapper();
    private final RetrievalMetricCalculator calculator = new RetrievalMetricCalculator();
    private final AnswerJudge answerJudge = AnswerJudgeFactory.create(objectMapper);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private LexicalTokenizer lexicalTokenizer;

    @Autowired
    private RetrievalCacheService retrievalCacheService;

    @Test
    @DisplayName("版本矩阵评测 - baseline → hybrid → rerank → query rewrite")
    void compareRetrievalVersionsAgainstSeededCorpus() throws Exception {
        TenantInterceptor.TenantContext.set(1L);
        try {
            compareRetrievalVersionsAgainstSeededCorpusInternal();
        } finally {
            TenantInterceptor.TenantContext.clear();
        }
    }

    private void compareRetrievalVersionsAgainstSeededCorpusInternal() throws Exception {
        List<GoldenQaCase> goldenCases = EvaluationFixtures.loadGoldenCases(objectMapper, "/evaluation/golden_qa.jsonl");
        seedAdversarialGoldenCorpus(goldenCases);

        List<VersionScenario> scenarios = List.of(
                new VersionScenario("baseline-dense-only", denseOnlyConfig(), GoldenQaCase::question),
                new VersionScenario("hybrid-rrf", hybridConfig(false), GoldenQaCase::question),
                new VersionScenario("hybrid-local-rerank", hybridConfig(true), GoldenQaCase::question),
                new VersionScenario("hybrid-rerank-query-rewrite", hybridConfig(true), this::deterministicRewrite)
        );

        Map<String, VersionEvaluationResult> results = new LinkedHashMap<>();
        for (VersionScenario scenario : scenarios) {
            applyRetrievalConfig(scenario.retrievalConfigJson());
            retrievalCacheService.invalidateByKbId(VERSION_KB_ID);
            List<RetrievalRunRecord> runRecords = runScenario(goldenCases, scenario);
            RetrievalMetrics metrics = calculator.calculate(goldenCases, runRecords, 5);
            List<JudgeScore> judgeScores = goldenCases.stream()
                    .map(golden -> answerJudge.judge(golden, runRecords.stream()
                            .filter(record -> record.questionId().equals(golden.id()))
                            .findFirst()
                            .orElse(new RetrievalRunRecord(golden.id(), List.of(), ""))))
                    .toList();
            results.put(scenario.name(), new VersionEvaluationResult(metrics, judgeScores, runRecords));
        }

        writeReports(results);

        assertEquals(scenarios.size(), results.size());
        assertTrue(results.get("hybrid-rerank-query-rewrite").metrics().recallAtK()
                        > results.get("baseline-dense-only").metrics().recallAtK(),
                "query rewrite scenario should improve Recall@5 over dense-only baseline on seeded corpus");
        assertTrue(results.get("hybrid-rrf").metrics().recallAtK()
                        > results.get("baseline-dense-only").metrics().recallAtK(),
                "hybrid RRF should improve Recall@5 over dense-only baseline on seeded corpus");
        assertTrue(results.get("hybrid-local-rerank").metrics().mrr()
                        >= results.get("hybrid-rrf").metrics().mrr(),
                "local rerank should not regress MRR against hybrid RRF on seeded corpus");
    }

    private List<RetrievalRunRecord> runScenario(List<GoldenQaCase> goldenCases, VersionScenario scenario) {
        List<RetrievalRunRecord> runRecords = new ArrayList<>();
        for (int i = 0; i < goldenCases.size(); i++) {
            GoldenQaCase golden = goldenCases.get(i);
            String query = scenario.queryProvider().apply(golden);
            List<RetrievalResult> results = retrievalService.retrieve(vectorFor(i), VERSION_KB_ID, query);
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

    private void seedAdversarialGoldenCorpus(List<GoldenQaCase> goldenCases) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE kb_id = ?", VERSION_KB_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", VERSION_DOC_ID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", VERSION_KB_ID);

        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, retrieval_config, doc_count, status)
                VALUES (?, 'versioned-evaluation-corpus', 'Adversarial seeded corpus for versioned retrieval comparison', 1,
                        'deterministic-test-vector', ?::jsonb, 1, 'ACTIVE')
                """, VERSION_KB_ID, hybridConfig(true));
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count)
                VALUES (?, ?, 'versioned-golden-qa.jsonl', 'evaluation/versioned_golden_qa.jsonl', 'jsonl', 1,
                        'versioned-evaluation-corpus', 'COMPLETED', ?)
                """, VERSION_DOC_ID, VERSION_KB_ID, goldenCases.size() * 2);

        for (int i = 0; i < goldenCases.size(); i++) {
            GoldenQaCase golden = goldenCases.get(i);
            String keywords = String.join(" ", golden.expectedKeywords());
            String relevantContent = golden.question() + "\n" + golden.referenceAnswer() + "\n关键词：" + keywords;
            String relevantLexicalContent = lexicalTokenizer.buildLexicalContent(golden.referenceAnswer() + " " + keywords);
            String relevantMetadata = "{\"evalChunkId\":\"" + golden.relevantChunkIds().get(0)
                    + "\",\"source\":\"versioned_golden_qa\",\"kind\":\"relevant\"}";
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (id, doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?), ?::jsonb)
                    """,
                    RELEVANT_CHUNK_ID_BASE + i,
                    VERSION_DOC_ID,
                    VERSION_KB_ID,
                    i * 2,
                    relevantContent,
                    relevantLexicalContent,
                    Math.max(1, relevantLexicalContent.length() / 2),
                    vectorStringFor(i + goldenCases.size() + 16),
                    relevantLexicalContent,
                    relevantMetadata);

            String distractorContent = "向量近邻干扰块：这是用于评测 dense-only baseline 的无关内容，"
                    + "不包含标准答案关键词。questionId=" + golden.id();
            String distractorLexicalContent = lexicalTokenizer.buildLexicalContent(distractorContent);
            String distractorMetadata = "{\"evalChunkId\":\"D-" + golden.id()
                    + "\",\"source\":\"versioned_golden_qa\",\"kind\":\"dense_distractor\"}";
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (id, doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?), ?::jsonb)
                    """,
                    DISTRACTOR_CHUNK_ID_BASE + i,
                    VERSION_DOC_ID,
                    VERSION_KB_ID,
                    i * 2 + 1,
                    distractorContent,
                    distractorLexicalContent,
                    Math.max(1, distractorContent.length() / 2),
                    vectorStringFor(i),
                    distractorLexicalContent,
                    distractorMetadata);
        }
    }

    private void applyRetrievalConfig(String configJson) {
        jdbcTemplate.update("UPDATE knowledge_base SET retrieval_config = ?::jsonb WHERE id = ?", configJson, VERSION_KB_ID);
    }

    private String deterministicRewrite(GoldenQaCase golden) {
        String technicalKeywords = golden.expectedKeywords().stream()
                .filter(keyword -> keyword.matches(".*[A-Za-z0-9].*"))
                .collect(Collectors.joining(" "));
        return technicalKeywords.isBlank()
                ? String.join(" ", golden.expectedKeywords())
                : technicalKeywords;
    }

    private String evalChunkId(Long numericChunkId) {
        return jdbcTemplate.queryForObject(
                "SELECT metadata->>'evalChunkId' FROM document_chunk WHERE id = ?",
                String.class,
                numericChunkId
        );
    }

    private void writeReports(Map<String, VersionEvaluationResult> results) throws IOException {
        Path outputDir = Path.of("target", "evaluation");
        Files.createDirectories(outputDir);
        Map<String, Object> json = new LinkedHashMap<>();
        StringBuilder markdown = new StringBuilder("# IntelliBase Versioned Retrieval Evaluation\n\n")
                .append("Seeded deterministic corpus for pipeline comparison. ")
                .append("This validates the version-comparison harness, not real embedding quality.\n\n")
                .append("| Version | Recall@5 | MRR | Hit Rate@5 | Faithfulness | Answer Relevance |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");

        for (Map.Entry<String, VersionEvaluationResult> entry : results.entrySet()) {
            VersionEvaluationResult result = entry.getValue();
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
            Files.writeString(outputDir.resolve("versioned-" + entry.getKey() + "-run.jsonl"), result.runRecords().stream()
                    .map(record -> {
                        try {
                            return jsonlMapper.writeValueAsString(record);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
        }

        Files.writeString(outputDir.resolve("versioned-comparison-report.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("versioned-comparison-metrics.json"), objectMapper.writeValueAsString(json), StandardCharsets.UTF_8);
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
                 "denseTopK":5,"sparseTopK":20,"fusionTopK":20,"finalTopK":5,
                 "denseWeight":0.55,"sparseWeight":0.45}
                """.formatted(rerankEnabled);
    }

    private float[] vectorFor(int index) {
        float[] vector = new float[1536];
        vector[index % vector.length] = 1.0F;
        return vector;
    }

    private String vectorStringFor(int index) {
        float[] vector = vectorFor(index);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    private String pct(double value) {
        return String.format("%.2f%%", value * 100D);
    }

    private record VersionScenario(String name,
                                   String retrievalConfigJson,
                                   Function<GoldenQaCase, String> queryProvider) {
    }

    private record VersionEvaluationResult(RetrievalMetrics metrics,
                                           List<JudgeScore> judgeScores,
                                           List<RetrievalRunRecord> runRecords) {
    }
}
