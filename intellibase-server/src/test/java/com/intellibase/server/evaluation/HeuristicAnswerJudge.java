package com.intellibase.server.evaluation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic RAGAS-style fallback used in CI when no LLM judge key is available.
 * It does not replace manual/LLM judging; it keeps the evaluation command runnable.
 */
public class HeuristicAnswerJudge implements AnswerJudge {

    @Override
    public JudgeScore judge(GoldenQaCase goldenCase, RetrievalRunRecord runRecord) {
        String answer = normalize(runRecord.generatedAnswer());
        String context = normalize(String.join("\n", runRecord.candidates().stream()
                .map(RetrievedCandidate::content)
                .filter(content -> content != null && !content.isBlank())
                .toList()));

        double answerRelevance = keywordCoverage(answer, goldenCase.expectedKeywords());
        double faithfulness = answer.isBlank() ? 0D : sentenceSupport(answer, context);
        String reason = "heuristic keywordCoverage=" + round(answerRelevance)
                + ", contextSupport=" + round(faithfulness);
        return new JudgeScore(goldenCase.id(), faithfulness, answerRelevance, reason);
    }

    private double keywordCoverage(String text, java.util.List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 1D;
        }
        long matched = keywords.stream()
                .filter(keyword -> text.contains(normalize(keyword)))
                .count();
        return matched / (double) keywords.size();
    }

    private double sentenceSupport(String answer, String context) {
        if (context.isBlank()) {
            return 0D;
        }
        Set<String> contextTerms = terms(context);
        String[] sentences = answer.split("[。.!?！？;；\\n]");
        int total = 0;
        int supported = 0;
        for (String sentence : sentences) {
            Set<String> sentenceTerms = terms(sentence);
            if (sentenceTerms.isEmpty()) {
                continue;
            }
            total++;
            long overlap = sentenceTerms.stream().filter(contextTerms::contains).count();
            if (overlap / (double) sentenceTerms.size() >= 0.35D) {
                supported++;
            }
        }
        return total == 0 ? 0D : supported / (double) total;
    }

    private Set<String> terms(String text) {
        String normalized = normalize(text).replaceAll("[^\\p{IsHan}a-z0-9_\\-]+", " ");
        Set<String> result = new HashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
