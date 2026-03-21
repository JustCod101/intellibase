package com.intellibase.server.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Embed 批次完成追踪器
 * <p>
 * 利用 Redis 的 INCRBY 原子操作追踪每个文档已完成的向量化批次数。
 * 解决并发消费时 {@code lastBatch} 标志无法保证顺序的竞态问题。
 *
 * <h3>核心思路</h3>
 * <pre>
 *   DocParseConsumer 发送 M 个批次 → 每个 EmbedBatchMessage 都携带 totalBatches = M
 *                                       ↓
 *   EmbedConsumer 消费完一个批次后调用 incrementAndGet(docId)
 *                                       ↓
 *   Redis: INCR embed:batch:progress:{docId}  → 返回当前已完成数
 *                                       ↓
 *   当 completedBatches == totalBatches 时，当前线程负责标记 COMPLETED 并清理 key
 * </pre>
 *
 * <h3>为什么用 Redis 而不是 JVM 内存 AtomicLong？</h3>
 * <ul>
 *   <li>EmbedConsumer 可能有多个实例（concurrency=2-3），甚至未来可能多节点部署</li>
 *   <li>Redis INCR 是单线程原子操作，天然无竞态</li>
 *   <li>Key 设置 TTL 兜底，即使异常也不会永久占用内存</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbedBatchTracker {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "embed:batch:progress:";

    /** Key 过期时间：24 小时兜底，防止异常情况下 key 永驻 */
    private static final long KEY_TTL_HOURS = 24;

    /**
     * 原子递增已完成批次数，并判断是否为最后一个完成的批次。
     *
     * @param docId        文档 ID
     * @param totalBatches 该文档的总批次数
     * @return true 表示当前批次是最后一个完成的（所有批次均已处理完毕），应触发 COMPLETED 状态更新
     */
    public boolean incrementAndCheck(Long docId, int totalBatches) {
        String key = KEY_PREFIX + docId;

        // INCR 是原子操作：多个并发消费者同时调用，Redis 保证每次返回唯一递增值
        Long completed = redisTemplate.opsForValue().increment(key);

        if (completed != null && completed == 1) {
            // 第一次写入时设置 TTL 兜底
            redisTemplate.expire(key, KEY_TTL_HOURS, TimeUnit.HOURS);
        }

        log.debug("批次进度: docId={}, completed={}/{}", docId, completed, totalBatches);

        if (completed != null && completed >= totalBatches) {
            // 清理 key，释放 Redis 内存
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    /**
     * 清理指定文档的批次追踪 key。
     * 用于文档删除或处理失败时的资源回收。
     *
     * @param docId 文档 ID
     */
    public void cleanup(Long docId) {
        redisTemplate.delete(KEY_PREFIX + docId);
    }
}
