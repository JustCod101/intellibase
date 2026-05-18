package com.intellibase.server.evaluation;

import java.util.List;

/**
 * Golden QA item used by offline RAG retrieval and answer-quality evaluation.
 */
public record GoldenQaCase(
        String id,
        String domain,
        String question,
        String referenceAnswer,
        List<String> relevantChunkIds,
        List<String> expectedKeywords
) {
}
