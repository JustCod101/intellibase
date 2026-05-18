package com.intellibase.server.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RetrievalMetricCalculator {

    public RetrievalMetrics calculate(List<GoldenQaCase> goldenCases,
                                      List<RetrievalRunRecord> runRecords,
                                      int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Map<String, RetrievalRunRecord> runByQuestionId = runRecords.stream()
                .collect(Collectors.toMap(RetrievalRunRecord::questionId, Function.identity()));

        List<QuestionRetrievalScore> questionScores = new ArrayList<>();
        for (GoldenQaCase goldenCase : goldenCases) {
            RetrievalRunRecord runRecord = runByQuestionId.get(goldenCase.id());
            List<RetrievedCandidate> topK = runRecord == null
                    ? List.of()
                    : runRecord.candidates().stream().limit(k).toList();
            questionScores.add(scoreQuestion(goldenCase, topK));
        }

        double recall = questionScores.stream().mapToDouble(QuestionRetrievalScore::recallAtK).average().orElse(0D);
        double mrr = questionScores.stream().mapToDouble(QuestionRetrievalScore::reciprocalRank).average().orElse(0D);
        double hitRate = questionScores.stream().filter(QuestionRetrievalScore::hit).count()
                / (double) Math.max(1, questionScores.size());
        return new RetrievalMetrics(k, goldenCases.size(), recall, mrr, hitRate, questionScores);
    }

    private QuestionRetrievalScore scoreQuestion(GoldenQaCase goldenCase, List<RetrievedCandidate> topK) {
        Set<String> relevant = new HashSet<>(goldenCase.relevantChunkIds());
        List<String> retrieved = topK.stream().map(RetrievedCandidate::chunkId).toList();
        long relevantRetrieved = retrieved.stream().filter(relevant::contains).distinct().count();
        double recallAtK = relevant.isEmpty() ? 0D : relevantRetrieved / (double) relevant.size();

        double reciprocalRank = 0D;
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                reciprocalRank = 1D / (i + 1D);
                break;
            }
        }

        return new QuestionRetrievalScore(
                goldenCase.id(),
                goldenCase.domain(),
                recallAtK,
                reciprocalRank,
                relevantRetrieved > 0,
                retrieved,
                goldenCase.relevantChunkIds());
    }
}
