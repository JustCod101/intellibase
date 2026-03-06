package com.intellibase.server.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 三级缓存命中率统计服务
 *
 * 统计维度：
 * - L1 (语义缓存): pgvector semantic_cache 表，相似度 > 0.95 直接返回
 * - L2 (检索缓存): Redis Hash，相同 query hash 的检索结果缓存
 * - L3 (文档缓存): Redis String，热点 chunk 内容缓存
 * - DB (数据库穿透): 直接查询 pgvector document_chunk 表
 */
@Slf4j
@Service
public class CacheStatsService {

    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l1Misses = new AtomicLong(0);

    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong l2Misses = new AtomicLong(0);

    private final AtomicLong l3Hits = new AtomicLong(0);
    private final AtomicLong l3Misses = new AtomicLong(0);

    private final AtomicLong dbQueries = new AtomicLong(0);

    // ===== 记录方法 =====

    public void recordL1Hit() {
        l1Hits.incrementAndGet();
    }

    public void recordL1Miss() {
        l1Misses.incrementAndGet();
    }

    public void recordL2Hit() {
        l2Hits.incrementAndGet();
    }

    public void recordL2Miss() {
        l2Misses.incrementAndGet();
    }

    public void recordL3Hit(int count) {
        l3Hits.addAndGet(count);
    }

    public void recordL3Miss(int count) {
        l3Misses.addAndGet(count);
    }

    public void recordDbQuery() {
        dbQueries.incrementAndGet();
    }

    // ===== 查询方法 =====

    /**
     * 获取全部缓存统计信息，供 Actuator 端点或管理接口调用
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("l1_semantic_cache", buildLevelStats(l1Hits.get(), l1Misses.get()));
        stats.put("l2_retrieval_cache", buildLevelStats(l2Hits.get(), l2Misses.get()));
        stats.put("l3_chunk_cache", buildLevelStats(l3Hits.get(), l3Misses.get()));
        stats.put("db_queries", dbQueries.get());

        long totalRequests = l1Hits.get() + l1Misses.get();
        long totalCacheHits = l1Hits.get() + l2Hits.get();
        stats.put("overall_cache_hit_rate", totalRequests > 0
                ? String.format("%.2f%%", (double) totalCacheHits / totalRequests * 100) : "N/A");

        return stats;
    }

    /**
     * 重置统计计数器
     */
    public void reset() {
        l1Hits.set(0);
        l1Misses.set(0);
        l2Hits.set(0);
        l2Misses.set(0);
        l3Hits.set(0);
        l3Misses.set(0);
        dbQueries.set(0);
    }

    private Map<String, Object> buildLevelStats(long hits, long misses) {
        long total = hits + misses;
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("hits", hits);
        level.put("misses", misses);
        level.put("total", total);
        level.put("hit_rate", total > 0
                ? String.format("%.2f%%", (double) hits / total * 100) : "N/A");
        return level;
    }

}
