package com.intellibase.server.service.rag;

import com.intellibase.server.domain.dto.RetrievalConfig;
import com.intellibase.server.domain.vo.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责加权 RRF 融合和规则型 rerank。
 */
@Service
@RequiredArgsConstructor
public class HybridRanker {

    private final LexicalTokenizer lexicalTokenizer;
    private final RetrievalConfigResolver retrievalConfigResolver;

    public List<RetrievalResult> rank(String query,
                                      List<RetrievalResult> candidates,
                                      List<Long> denseRankedChunkIds,
                                      List<Long> sparseRankedChunkIds,
                                      RetrievalConfig config) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> denseRanks = buildRankMap(denseRankedChunkIds);
        Map<Long, Integer> sparseRanks = buildRankMap(sparseRankedChunkIds);

        for (RetrievalResult candidate : candidates) {
            candidate.setMatchType(resolveMatchType(candidate));
            candidate.setFusedScore(computeFusedScore(candidate.getChunkId(), denseRanks, sparseRanks, config));
        }

        List<RetrievalResult> fusionCandidates = candidates.stream()
                .sorted(Comparator
                        .comparing((RetrievalResult result) -> safe(result.getFusedScore()), Comparator.reverseOrder())
                        .thenComparing(result -> safe(result.getDenseScore()), Comparator.reverseOrder())
                        .thenComparing(result -> safe(result.getSparseScore()), Comparator.reverseOrder()))
                .limit(config.getFusionTopK())
                .toList();

        Map<Long, Double> normalizedDense = normalize(fusionCandidates, RetrievalResult::getDenseScore);
        Map<Long, Double> normalizedSparse = normalize(fusionCandidates, RetrievalResult::getSparseScore);
        List<String> queryTokens = lexicalTokenizer.tokenizeForMatch(query);

        for (RetrievalResult candidate : fusionCandidates) {
            double fusedScore = candidate.getFusedScore() != null ? candidate.getFusedScore() : 0D;
            double finalScore = fusedScore;
            if (Boolean.TRUE.equals(config.getRerankEnabled())) {
                Set<String> contentTokens = lexicalTokenizer.tokenSet(candidate.getContent());
                double tokenCoverage = computeTokenCoverage(queryTokens, contentTokens);
                double exactTermBoost = hasExactTechnicalMatch(queryTokens, contentTokens) ? 1D : 0D;
                finalScore = 0.40 * fusedScore
                        + 0.20 * normalizedDense.getOrDefault(candidate.getChunkId(), 0D)
                        + 0.15 * normalizedSparse.getOrDefault(candidate.getChunkId(), 0D)
                        + 0.15 * tokenCoverage
                        + 0.10 * exactTermBoost;
            }
            candidate.setRerankScore(finalScore);
            candidate.setScore(finalScore);
        }

        return fusionCandidates.stream()
                .sorted(Comparator
                        .comparingDouble(RetrievalResult::getScore).reversed()
                        .thenComparing(result -> safe(result.getFusedScore()), Comparator.reverseOrder())
                        .thenComparing(result -> safe(result.getDenseScore()), Comparator.reverseOrder())
                        .thenComparing(result -> safe(result.getSparseScore()), Comparator.reverseOrder()))
                .limit(config.getFinalTopK())
                .toList();
    }

    private double computeFusedScore(Long chunkId,
                                     Map<Long, Integer> denseRanks,
                                     Map<Long, Integer> sparseRanks,
                                     RetrievalConfig config) {
        int rrfK = retrievalConfigResolver.getRrfK();
        double score = 0D;
        Integer denseRank = denseRanks.get(chunkId);
        if (denseRank != null) {
            score += config.getDenseWeight() / (rrfK + denseRank);
        }
        Integer sparseRank = sparseRanks.get(chunkId);
        if (sparseRank != null) {
            score += config.getSparseWeight() / (rrfK + sparseRank);
        }
        return score;
    }

    private Map<Long, Integer> buildRankMap(List<Long> rankedChunkIds) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (int i = 0; i < rankedChunkIds.size(); i++) {
            ranks.put(rankedChunkIds.get(i), i + 1);
        }
        return ranks;
    }

    private Map<Long, Double> normalize(List<RetrievalResult> candidates,
                                        java.util.function.Function<RetrievalResult, Double> extractor) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (RetrievalResult candidate : candidates) {
            Double raw = extractor.apply(candidate);
            if (raw == null) {
                continue;
            }
            min = Math.min(min, raw);
            max = Math.max(max, raw);
        }

        Map<Long, Double> normalized = new HashMap<>();
        if (min == Double.POSITIVE_INFINITY) {
            return normalized;
        }

        for (RetrievalResult candidate : candidates) {
            Double raw = extractor.apply(candidate);
            if (raw == null) {
                normalized.put(candidate.getChunkId(), 0D);
                continue;
            }
            if (Double.compare(max, min) == 0) {
                normalized.put(candidate.getChunkId(), raw > 0D ? 1D : 0D);
                continue;
            }
            normalized.put(candidate.getChunkId(), (raw - min) / (max - min));
        }
        return normalized;
    }

    private String resolveMatchType(RetrievalResult result) {
        boolean hasDense = result.getDenseScore() != null;
        boolean hasSparse = result.getSparseScore() != null;
        if (hasDense && hasSparse) {
            return "HYBRID";
        }
        if (hasDense) {
            return "DENSE";
        }
        return "SPARSE";
    }

    private double computeTokenCoverage(List<String> queryTokens, Set<String> contentTokens) {
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0D;
        }
        long matches = queryTokens.stream().filter(contentTokens::contains).count();
        return matches / (double) queryTokens.size();
    }

    private boolean hasExactTechnicalMatch(List<String> queryTokens, Set<String> contentTokens) {
        return queryTokens.stream()
                .filter(this::isTechnicalToken)
                .anyMatch(contentTokens::contains);
    }

    private boolean isTechnicalToken(String token) {
        return token.contains("_")
                || token.chars().anyMatch(Character::isDigit)
                || (token.length() >= 4 && token.chars().allMatch(ch -> ch < 128));
    }

    private Double safe(Double value) {
        return value != null ? value : 0D;
    }
}
