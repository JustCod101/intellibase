package com.intellibase.server.service.rag;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 【三级缓存】文档块缓存服务 (L3 Chunk Cache Service)
 * <p>
 * 缓存层级：L0 (Caffeine JVM本地) → L3 (Redis) → DB (PostgreSQL)
 * <p>
 * L0 本地缓存拦截在 Redis 之前，热点文档块可以做到零网络延迟。
 * L3 Redis 作为跨实例共享的缓存层。
 * DB 作为最终数据源，miss 时从 DB 加载并回填 L0 + L3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkCacheService {

    private final StringRedisTemplate redisTemplate;
    private final DocumentChunkMapper documentChunkMapper;
    private final CacheStatsService cacheStatsService;

    private static final String KEY_PREFIX = "chunk:";

    @Value("${rag.l3-chunk-cache-ttl-hours:2}")
    private int ttlHours;

    @Value("${rag.l0-local-cache-ttl-minutes:10}")
    private int l0TtlMinutes;

    @Value("${rag.l0-local-cache-max-size:1000}")
    private int l0MaxSize;

    /** L0: JVM 进程内 Caffeine 本地缓存，Key = chunkId, Value = content */
    private Cache<Long, String> localCache;

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .maximumSize(l0MaxSize)
                .expireAfterWrite(l0TtlMinutes, TimeUnit.MINUTES)
                .build();
        log.info("L0 本地文档块缓存已初始化: maxSize={}, ttl={}min", l0MaxSize, l0TtlMinutes);
    }

    /**
     * 批量获取文档碎块内容：L0 (Caffeine) → L3 (Redis MGET) → DB
     *
     * @param chunkIds 需要获取的文档碎块 ID 列表
     * @return 文档块列表（保持请求顺序）
     */
    public List<DocumentChunk> getChunks(List<Long> chunkIds) {
        List<DocumentChunk> result = new ArrayList<>(chunkIds.size());
        List<Integer> l0MissIndexes = new ArrayList<>(); // 记录 L0 未命中的位置
        List<Long> l0MissIds = new ArrayList<>();         // 对应的 chunkId
        int l0HitCount = 0;

        // ===== 第一层：L0 Caffeine 本地缓存（零网络延迟） =====
        for (int i = 0; i < chunkIds.size(); i++) {
            Long chunkId = chunkIds.get(i);
            String cached = localCache.getIfPresent(chunkId);
            if (cached != null) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId(chunkId);
                chunk.setContent(cached);
                result.add(chunk);
                l0HitCount++;
            } else {
                result.add(null); // 占位
                l0MissIndexes.add(i);
                l0MissIds.add(chunkId);
            }
        }

        if (l0HitCount > 0) {
            cacheStatsService.recordL0Hit();
        }

        // 如果 L0 全部命中，直接返回
        if (l0MissIds.isEmpty()) {
            log.debug("L0/L3 文档缓存: 请求={}, L0命中={}", chunkIds.size(), l0HitCount);
            return result;
        }
        cacheStatsService.recordL0Miss();

        // ===== 第二层：L3 Redis MGET 批量读取 =====
        List<String> redisKeys = l0MissIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toList();
        List<String> redisValues = redisTemplate.opsForValue().multiGet(redisKeys);

        List<Integer> redisMissIndexes = new ArrayList<>(); // 在 l0MissIndexes 中的索引
        List<Long> redisMissIds = new ArrayList<>();
        int l3HitCount = 0;

        for (int j = 0; j < l0MissIds.size(); j++) {
            String cached = redisValues != null ? redisValues.get(j) : null;
            Long chunkId = l0MissIds.get(j);
            int originalIndex = l0MissIndexes.get(j);

            if (cached != null) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId(chunkId);
                chunk.setContent(cached);
                result.set(originalIndex, chunk);
                // 回填 L0
                localCache.put(chunkId, cached);
                l3HitCount++;
            } else {
                redisMissIndexes.add(j);
                redisMissIds.add(chunkId);
            }
        }

        if (l3HitCount > 0) {
            cacheStatsService.recordL3Hit(l3HitCount);
        }
        if (!redisMissIds.isEmpty()) {
            cacheStatsService.recordL3Miss(redisMissIds.size());
        }

        // ===== 第三层：DB 批量加载，回填 L0 + L3 =====
        if (!redisMissIds.isEmpty()) {
            List<DocumentChunk> dbChunks = documentChunkMapper.selectBatchIds(redisMissIds);

            Map<Long, DocumentChunk> dbMap = new HashMap<>();
            for (DocumentChunk c : dbChunks) {
                dbMap.put(c.getId(), c);
            }

            List<DocumentChunk> toCache = new ArrayList<>();
            for (int j : redisMissIndexes) {
                Long chunkId = l0MissIds.get(j);
                int originalIndex = l0MissIndexes.get(j);
                DocumentChunk dbChunk = dbMap.get(chunkId);
                if (dbChunk != null) {
                    result.set(originalIndex, dbChunk);
                    toCache.add(dbChunk);
                    // 回填 L0
                    localCache.put(chunkId, dbChunk.getContent());
                }
            }

            // Pipeline 批量回填 L3 Redis
            if (!toCache.isEmpty()) {
                pipelineSetChunks(toCache);
            }
        }

        log.debug("L0/L3 文档缓存: 请求={}, L0命中={}, L3命中={}, DB穿透={}",
                chunkIds.size(), l0HitCount, l3HitCount, redisMissIds.size());

        result.removeIf(Objects::isNull);
        return result;
    }

    /**
     * 通过 Redis Pipeline 批量写入多个 chunk 到 L3 缓存
     */
    private void pipelineSetChunks(List<DocumentChunk> chunks) {
        try {
            redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (DocumentChunk chunk : chunks) {
                        operations.opsForValue().set(
                                KEY_PREFIX + chunk.getId(),
                                chunk.getContent(),
                                ttlHours, TimeUnit.HOURS);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("L3 文档缓存 Pipeline 批量写入失败: count={}", chunks.size(), e);
        }
    }

    /**
     * 按分块ID列表批量清除缓存（L0 + L3）
     */
    public void invalidateByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        // L0: 清除本地缓存
        localCache.invalidateAll(chunkIds);

        // L3: 清除 Redis
        try {
            List<String> keys = chunkIds.stream()
                    .map(id -> KEY_PREFIX + id)
                    .toList();
            redisTemplate.delete(keys);
            log.info("L0+L3 文档缓存已清除: 清除数量={}", keys.size());
        } catch (Exception e) {
            log.warn("L3 文档缓存清除失败", e);
        }
    }

}
