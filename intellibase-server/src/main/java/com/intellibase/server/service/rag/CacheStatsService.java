package com.intellibase.server.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【三级缓存监控中心】缓存命中率统计服务 (Cache Stats Service)
 * <p>
 * 核心原理（大白话解释）：
 * 我们费了九牛二虎之力搭建了三级缓存（L1语义、L2检索、L3碎块），但到底有没有用？省了多少钱？
 * 这个类就像是收费站的会计，手里拿着几个计数器（AtomicLong），专门负责记账。
 * 每次系统去缓存里找东西，不管找到了（Hit）还是没找到（Miss），都会来这里报备一下。
 * 
 * 统计维度：
 * - L1 (一级语义缓存): 在数据库里找最相似的历史问答。相似度 > 0.95 直接白嫖答案。
 * - L2 (二级检索缓存): 在 Redis 里找别人刚刚搜过的文档片段列表。
 * - L3 (三级文档缓存): 在 Redis 里找经常被访问的零碎文档段落。
 * - DB (数据库穿透): 缓存全没命中，老老实实去底层数据库查询的次数。
 */
@Slf4j
@Service
public class CacheStatsService {

    // AtomicLong 是线程安全的计数器。因为可能有很多用户同时提问，普通的 long++ 会算错账。

    // L0 本地缓存（Caffeine JVM 内存）账本
    private final AtomicLong l0Hits = new AtomicLong(0);   // 命中次数（零网络延迟）
    private final AtomicLong l0Misses = new AtomicLong(0); // 未命中次数

    // 一级缓存（语义）账本
    private final AtomicLong l1Hits = new AtomicLong(0);   // 命中次数（省大钱了）
    private final AtomicLong l1Misses = new AtomicLong(0); // 未命中次数

    // 二级缓存（检索）账本
    private final AtomicLong l2Hits = new AtomicLong(0);   // 命中次数（省下昂贵的数据库向量搜索）
    private final AtomicLong l2Misses = new AtomicLong(0); // 未命中次数

    // 三级缓存（碎块）账本
    private final AtomicLong l3Hits = new AtomicLong(0);   // 命中次数（省下数据库读取）
    private final AtomicLong l3Misses = new AtomicLong(0); // 未命中次数

    // 底层数据库账本
    private final AtomicLong dbQueries = new AtomicLong(0); // 彻底穿透缓存，去查底层数据库的次数

    // ===== 记账方法（供其他服务在干活时顺手调用） =====

    public void recordL0Hit() {
        l0Hits.incrementAndGet();
    }

    public void recordL0Miss() {
        l0Misses.incrementAndGet();
    }

    public void recordL1Hit() {
        l1Hits.incrementAndGet(); // 计数器 + 1
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

    // 因为三级缓存经常是一次性批量拿好几个碎块，所以允许一次加好几笔账
    public void recordL3Hit(int count) {
        l3Hits.addAndGet(count);
    }

    public void recordL3Miss(int count) {
        l3Misses.addAndGet(count);
    }

    public void recordDbQuery() {
        dbQueries.incrementAndGet();
    }

    // ===== 查账方法 =====

    /**
     * 老板（前端控制台或监控系统）来查账了，交出汇总报表
     */
    public Map<String, Object> getStats() {
        // 使用 LinkedHashMap 保证输出的 JSON 顺序和这里写入的顺序一样，整整齐齐
        Map<String, Object> stats = new LinkedHashMap<>();

        // 分别汇报每一级的战况
        stats.put("l0_local_cache", buildLevelStats(l0Hits.get(), l0Misses.get()));
        stats.put("l1_semantic_cache", buildLevelStats(l1Hits.get(), l1Misses.get()));
        stats.put("l2_retrieval_cache", buildLevelStats(l2Hits.get(), l2Misses.get()));
        stats.put("l3_chunk_cache", buildLevelStats(l3Hits.get(), l3Misses.get()));
        stats.put("db_queries", dbQueries.get());

        // 计算一个终极指标：总的缓存拦截率
        // 总请求数 = 经过第一级缓存的总人数（命中的 + 没命中的）
        long totalRequests = l1Hits.get() + l1Misses.get();
        // 成功在 L1 或 L2 直接拿到最终拼装材料的次数
        long totalCacheHits = l1Hits.get() + l2Hits.get();
        // 算出百分比，比如 "85.50%"
        stats.put("overall_cache_hit_rate", totalRequests > 0
                ? String.format("%.2f%%", (double) totalCacheHits / totalRequests * 100) : "N/A");

        return stats;
    }

    /**
     * 账本清零（比如系统重启，或者管理员点了一下重置按钮）
     */
    public void reset() {
        l0Hits.set(0);
        l0Misses.set(0);
        l1Hits.set(0);
        l1Misses.set(0);
        l2Hits.set(0);
        l2Misses.set(0);
        l3Hits.set(0);
        l3Misses.set(0);
        dbQueries.set(0);
    }

    /**
     * 内部工具方法：用来算某一级的具体百分比
     * 格式：{ hits: 10, misses: 2, total: 12, hit_rate: "83.33%" }
     */
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
