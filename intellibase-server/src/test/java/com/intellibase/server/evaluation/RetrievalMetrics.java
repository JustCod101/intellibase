package com.intellibase.server.evaluation;

import java.util.List;

public record RetrievalMetrics(
        int k,
        int totalQuestions,
        double recallAtK,
        double mrr,
        double hitRateAtK,
        List<QuestionRetrievalScore> questionScores
) {
}
