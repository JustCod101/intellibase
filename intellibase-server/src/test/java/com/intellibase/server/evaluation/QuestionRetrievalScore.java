package com.intellibase.server.evaluation;

import java.util.List;

public record QuestionRetrievalScore(
        String questionId,
        String domain,
        double recallAtK,
        double reciprocalRank,
        boolean hit,
        List<String> retrievedChunkIds,
        List<String> relevantChunkIds
) {
}
