package com.intellibase.server.service.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 消息幂等性服务
 * <p>
 * 基于 Redis SETNX 实现消费端去重，防止消息重复投递导致的副作用：
 * <ul>
 *   <li>同一文档被重复解析（Tika + MinIO 重复下载）</li>
 *   <li>document_chunk 表产生重复数据</li>
 *   <li>Embedding API 被重复调用浪费 token</li>
 * </ul>
 *
 * <h3>设计思路</h3>
 * <pre>
 *   Producer 端：为每条消息生成 UUID 作为 messageId
 *   Consumer 端：消费前调用 tryAcquire(messageId)
 *     ├─ SETNX 成功 → 首次消费，执行业务逻辑
 *     └─ SETNX 失败 → 重复消息，直接 ACK 丢弃
 * </pre>
 *
 * <h3>Key 过期策略</h3>
 * <ul>
 *   <li>默认 TTL = 24h，远大于消息最大重试窗口（2s + 6s + 18s ≈ 26s）</li>
 *   <li>TTL 到期后 key 自动清理，不会永久占用 Redis 内存</li>
 *   <li>对于极端场景（如手动重放消息），24h 窗口足够覆盖</li>
 * </ul>
 *
 * <h3>为什么用 Redis 而不是数据库？</h3>
 * <ul>
 *   <li>SETNX 是 O(1) 原子操作，性能远高于 DB 唯一索引冲突</li>
 *   <li>消费者可能有多实例并发，Redis 单线程保证互斥</li>
 *   <li>自动 TTL 清理，无需额外的过期任务</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "mq:idempotent:";

    /** 幂等 Key 过期时间：24 小时 */
    private static final long KEY_TTL_HOURS = 24;

    /**
     * 尝试获取消息的处理权。
     * <p>
     * 利用 Redis SETNX（SET if Not eXists）实现：
     * <ul>
     *   <li>如果 key 不存在 → 设置成功，返回 true（首次消费）</li>
     *   <li>如果 key 已存在 → 设置失败，返回 false（重复消息）</li>
     * </ul>
     *
     * @param messageId 消息唯一标识（UUID）
     * @return true = 首次处理，false = 重复消息应跳过
     */
    public boolean tryAcquire(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            // messageId 为空时降级为非幂等模式（兼容旧消息）
            log.warn("消息缺少 messageId，跳过幂等检查");
            return true;
        }

        String key = KEY_PREFIX + messageId;
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", KEY_TTL_HOURS, TimeUnit.HOURS);

        if (Boolean.TRUE.equals(success)) {
            return true;
        }

        log.warn("检测到重复消息，已跳过处理: messageId={}", messageId);
        return false;
    }

    /**
     * 释放幂等锁。
     * <p>
     * 仅用于消费失败需要允许重试的场景（如抛出异常触发 RetryInterceptor）。
     * 正常消费成功后 <b>不要</b> 调用此方法，让 key 自然过期即可。
     *
     * @param messageId 消息唯一标识
     */
    public void release(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            redisTemplate.delete(KEY_PREFIX + messageId);
        }
    }
}
