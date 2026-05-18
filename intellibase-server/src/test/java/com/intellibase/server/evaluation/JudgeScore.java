package com.intellibase.server.evaluation;

public record JudgeScore(
        String questionId,
        double faithfulness,
        double answerRelevance,
        String reason
) {
}
