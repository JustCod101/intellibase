package com.intellibase.server.evaluation;

/**
 * One retrieved chunk in rank order. chunkId is a String so the same evaluator can
 * handle DB numeric IDs and stable fixture IDs used by synthetic corpora.
 */
public record RetrievedCandidate(
        String chunkId,
        int rank,
        double score,
        String content
) {
}
