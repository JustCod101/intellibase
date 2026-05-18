package com.intellibase.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final RetrievalMetricCalculator calculator = new RetrievalMetricCalculator();
    private final AnswerJudge answerJudge = AnswerJudgeFactory.create(objectMapper);

    @Test
    @DisplayName("离线评测基线 - 输出 Recall@5 / MRR / Hit Rate 与 RAGAS 风格分数")
    void evaluateBaselineFixture() throws IOException {
        List<GoldenQaCase> goldenCases = EvaluationFixtures.loadGoldenCases(objectMapper, "/evaluation/golden_qa.jsonl");
        List<RetrievalRunRecord> baseline = EvaluationFixtures.loadRunRecords(objectMapper, "/evaluation/baseline_run.jsonl");

        RetrievalMetrics metrics = calculator.calculate(goldenCases, baseline, 5);
        List<JudgeScore> judgeScores = goldenCases.stream()
                .map(golden -> answerJudge.judge(golden, baseline.stream()
                        .filter(record -> record.questionId().equals(golden.id()))
                        .findFirst()
                        .orElse(new RetrievalRunRecord(golden.id(), List.of(), ""))))
                .toList();

        writeReports(metrics, judgeScores);

        assertEquals(60, goldenCases.size(), "golden set must keep 50-100 cases");
        assertEquals(goldenCases.size(), baseline.size(), "baseline run must cover every golden question");
        assertTrue(metrics.recallAtK() > 0D, "baseline Recall@5 should be measurable");
        assertTrue(metrics.mrr() > 0D, "baseline MRR should be measurable");
        assertTrue(metrics.hitRateAtK() > 0D, "baseline Hit Rate@5 should be measurable");
    }

    private void writeReports(RetrievalMetrics metrics, List<JudgeScore> judgeScores) throws IOException {
        Path outputDir = Path.of("target", "evaluation");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("baseline-metrics.json"), objectMapper.writeValueAsString(Map.of(
                "retrieval", metrics,
                "judge", judgeScores
        )), StandardCharsets.UTF_8);

        double faithfulness = judgeScores.stream().mapToDouble(JudgeScore::faithfulness).average().orElse(0D);
        double answerRelevance = judgeScores.stream().mapToDouble(JudgeScore::answerRelevance).average().orElse(0D);
        Map<String, Double> recallByDomain = metrics.questionScores().stream()
                .collect(Collectors.groupingBy(QuestionRetrievalScore::domain,
                        Collectors.averagingDouble(QuestionRetrievalScore::recallAtK)));

        String markdown = "# IntelliBase RAG Baseline Evaluation\n\n"
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
        Files.writeString(outputDir.resolve("baseline-report.md"), markdown, StandardCharsets.UTF_8);
    }

    private String pct(double value) {
        return String.format("%.2f%%", value * 100D);
    }
}
