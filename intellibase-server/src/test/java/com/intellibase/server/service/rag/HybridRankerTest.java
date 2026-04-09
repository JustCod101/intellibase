package com.intellibase.server.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.config.HybridRetrievalProperties;
import com.intellibase.server.domain.dto.RetrievalConfig;
import com.intellibase.server.domain.vo.RetrievalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridRankerTest {

    private final RetrievalConfigResolver retrievalConfigResolver =
            new RetrievalConfigResolver(new ObjectMapper(), new HybridRetrievalProperties());
    private final HybridRanker hybridRanker =
            new HybridRanker(new LexicalTokenizer(), retrievalConfigResolver);

    @Test
    @DisplayName("重排 - 精确技术命中优先于泛语义命中")
    void rank_PrioritizesExactTechnicalMatch() {
        RetrievalConfig config = retrievalConfigResolver.defaultConfig();

        RetrievalResult semanticHit = RetrievalResult.builder()
                .chunkId(1L)
                .docId(1L)
                .content("冲突处理的一般说明。")
                .snippet("冲突处理的一般说明。")
                .denseScore(0.95)
                .build();

        RetrievalResult exactHit = RetrievalResult.builder()
                .chunkId(2L)
                .docId(2L)
                .content("HTTP_409 冲突时，应通过 order.create 幂等键重试。")
                .snippet("HTTP_409 冲突时，应通过 order.create 幂等键重试。")
                .sparseScore(0.78)
                .build();

        List<RetrievalResult> ranked = hybridRanker.rank(
                "如何处理 HTTP_409",
                new ArrayList<>(List.of(semanticHit, exactHit)),
                List.of(1L),
                List.of(2L),
                config
        );

        assertEquals(2L, ranked.get(0).getChunkId());
        assertEquals("SPARSE", ranked.get(0).getMatchType());
    }
}
