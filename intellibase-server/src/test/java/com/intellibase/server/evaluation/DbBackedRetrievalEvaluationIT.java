package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellibase.server.domain.vo.RetrievalResult;
import com.intellibase.server.service.rag.RetrievalCacheService;
import com.intellibase.server.service.rag.RetrievalService;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live PostgreSQL/pgvector-backed retrieval evaluation.
 * <p>
 * Disabled by default so unit tests do not depend on Docker. Run explicitly after starting
 * PostgreSQL with schema.sql:
 * <pre>
 * JAVA_HOME=$(/usr/libexec/java_home -v 17.0.18) \
 *   mvn -pl intellibase-server -Dtest=DbBackedRetrievalEvaluationIT \
 *   -Devaluation.db.enabled=true test
 * </pre>
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rag.rerank.external-enabled=false"
})
@EnabledIfSystemProperty(named = "evaluation.db.enabled", matches = "true")
class DbBackedRetrievalEvaluationIT {

    private static final long EVAL_KB_ID = 91_001L;
    private static final long EVAL_DOC_ID = 91_001L;
    private static final long EVAL_CHUNK_ID_BASE = 9_100_000L;

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
    private RetrievalCacheService retrievalCacheService;

    @Test
    @DisplayName("DB-backed 评测 - 使用真实 RetrievalService + PostgreSQL/pgvector 输出指标")
    void evaluateCurrentRetrievalAgainstSeededGoldenCorpus() throws Exception {
        List<GoldenQaCase> goldenCases = EvaluationFixtures.loadGoldenCases(objectMapper, "/evaluation/golden_qa.jsonl");
        seedGoldenCorpus(goldenCases);
        retrievalCacheService.invalidateByKbId(EVAL_KB_ID);

        List<RetrievalRunRecord> runRecords = new ArrayList<>();
        for (int i = 0; i < goldenCases.size(); i++) {
            GoldenQaCase golden = goldenCases.get(i);
            List<RetrievalResult> results = retrievalService.retrieve(vectorFor(i), EVAL_KB_ID, golden.question());
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

        RetrievalMetrics metrics = calculator.calculate(goldenCases, runRecords, 5);
        List<JudgeScore> judgeScores = goldenCases.stream()
                .map(golden -> answerJudge.judge(golden, runRecords.stream()
                        .filter(record -> record.questionId().equals(golden.id()))
                        .findFirst()
                        .orElse(new RetrievalRunRecord(golden.id(), List.of(), ""))))
                .toList();
        writeReports(metrics, judgeScores, runRecords);

        assertEquals(goldenCases.size(), runRecords.size());
        assertTrue(metrics.recallAtK() >= 0.95, "seeded DB-backed corpus should retrieve almost all golden chunks");
        assertTrue(metrics.hitRateAtK() >= 0.95, "seeded DB-backed corpus should hit almost all questions");
    }

    private void seedGoldenCorpus(List<GoldenQaCase> goldenCases) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE kb_id = ?", EVAL_KB_ID);
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", EVAL_DOC_ID);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", EVAL_KB_ID);

        jdbcTemplate.update("""
                INSERT INTO knowledge_base (id, name, description, tenant_id, embedding_model, retrieval_config, doc_count, status)
                VALUES (?, 'evaluation-golden-corpus', 'Seeded golden QA corpus for DB-backed retrieval evaluation', 1,
                        'deterministic-test-vector', ?::jsonb, 1, 'ACTIVE')
                """, EVAL_KB_ID, """
                {"preset":"GENERAL_QA","hybridEnabled":true,"rerankEnabled":true,
                 "denseTopK":20,"sparseTopK":20,"fusionTopK":15,"finalTopK":5,
                 "denseWeight":0.55,"sparseWeight":0.45}
                """);
        jdbcTemplate.update("""
                INSERT INTO document (id, kb_id, title, file_key, file_type, file_size, content_hash, parse_status, chunk_count)
                VALUES (?, ?, 'golden-qa.jsonl', 'evaluation/golden_qa.jsonl', 'jsonl', 1, 'evaluation-golden-corpus', 'COMPLETED', ?)
                """, EVAL_DOC_ID, EVAL_KB_ID, goldenCases.size());

        for (int i = 0; i < goldenCases.size(); i++) {
            GoldenQaCase golden = goldenCases.get(i);
            String lexicalContent = (golden.question() + " " + golden.referenceAnswer() + " "
                    + String.join(" ", golden.expectedKeywords())).toLowerCase();
            String metadata = "{\"evalChunkId\":\"" + golden.relevantChunkIds().get(0) + "\",\"source\":\"golden_qa\"}";
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (id, doc_id, kb_id, chunk_index, content, lexical_content, token_count, embedding, lexical_vector, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector('simple', ?), ?::jsonb)
                    """,
                    EVAL_CHUNK_ID_BASE + i,
                    EVAL_DOC_ID,
                    EVAL_KB_ID,
                    i,
                    golden.referenceAnswer() + " 关键词：" + String.join("、", golden.expectedKeywords()),
                    lexicalContent,
                    Math.max(1, lexicalContent.length() / 2),
                    vectorStringFor(i),
                    lexicalContent,
                    metadata);
        }
    }

    private String evalChunkId(Long numericChunkId) {
        return jdbcTemplate.queryForObject(
                "SELECT metadata->>'evalChunkId' FROM document_chunk WHERE id = ?",
                String.class,
                numericChunkId
        );
    }

    private void writeReports(RetrievalMetrics metrics,
                              List<JudgeScore> judgeScores,
                              List<RetrievalRunRecord> runRecords) throws IOException {
        Path outputDir = Path.of("target", "evaluation");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("db-backed-current-run.jsonl"), runRecords.stream()
                .map(record -> {
                    try {
                        return jsonlMapper.writeValueAsString(record);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.joining("\n")), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("db-backed-current-metrics.json"), objectMapper.writeValueAsString(Map.of(
                "retrieval", metrics,
                "judge", judgeScores
        )), StandardCharsets.UTF_8);

        double faithfulness = judgeScores.stream().mapToDouble(JudgeScore::faithfulness).average().orElse(0D);
        double answerRelevance = judgeScores.stream().mapToDouble(JudgeScore::answerRelevance).average().orElse(0D);
        Map<String, Double> recallByDomain = metrics.questionScores().stream()
                .collect(Collectors.groupingBy(QuestionRetrievalScore::domain,
                        Collectors.averagingDouble(QuestionRetrievalScore::recallAtK)));

        String markdown = "# IntelliBase DB-backed Retrieval Evaluation\n\n"
                + "| Metric | Value |\n"
                + "|---|---:|\n"
                + "| Questions | " + metrics.totalQuestions() + " |\n"
                + "| Recall@" + metrics.k() + " | " + pct(metrics.recallAtK()) + " |\n"
                + "| MRR | " + pct(metrics.mrr()) + " |\n"
                + "| Hit Rate@" + metrics.k() + " | " + pct(metrics.hitRateAtK()) + " |\n"
                + "| Faithfulness (heuristic) | " + pct(faithfulness) + " |\n"
                + "| Answer Relevance (heuristic) | " + pct(answerRelevance) + " |\n\n"
                + "## Recall@" + metrics.k() + " by domain\n\n"
                + recallByDomain.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> "- " + entry.getKey() + ": " + pct(entry.getValue()))
                    .collect(Collectors.joining("\n"))
                + "\n";
        Files.writeString(outputDir.resolve("db-backed-current-report.md"), markdown, StandardCharsets.UTF_8);
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
}
