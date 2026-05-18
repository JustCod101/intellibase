package com.intellibase.server.evaluation;

import java.util.List;

/**
 * Retrieval output for one golden question.
 */
public record RetrievalRunRecord(
        String questionId,
        List<RetrievedCandidate> candidates,
        String generatedAnswer
) {
}
