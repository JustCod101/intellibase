package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 【三级缓存】文档块缓存服务 (L3 Chunk Cache Service)
 * <p>
 * 核心原理（大白话解释）：
 * 如果前两级缓存都没命中，系统最后只能去底层数据库（PostgreSQL）里翻找文档了。
 * 知识库里的文档被切成了成千上万个“小碎块”（DocumentChunk）。
 * 每次从数据库里把这些包含大段文字的“小碎块”捞出来，很占用数据库连接和磁盘IO。
 * 所以，我们就把那些**经常被搜到的“热点碎块”**，顺手存到 Redis 内存里。
 * 下次还要用这个碎块拼答案时，直接从 Redis 内存里拿，速度快得多。
 * <p>
 * 缓存策略：
 * 怎么防止 Redis 被塞满呢？
 * 我们给 Redis 设置了 maxmemory-policy allkeys-lru 规则。
 * 意思是如果内存满了，Redis 会自动把那些“最久没人看过”的冷门碎块踢出去，给新碎块腾位置。
 * <p>
 * Redis Key 格式长这样: chunk:{碎块的数据库主键ID}
 * TTL(存活时间): 默认 2 小时。即使内存没满，2小时后也会自动清理，保证数据相对新鲜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkCacheService {

    // 操作 Redis 数据库的工具人
    private final StringRedisTemplate redisTemplate;
    // 操作底层数据库（PostgreSQL）去捞真实碎块的工具人
    private final DocumentChunkMapper documentChunkMapper;
    // 缓存命中统计
    private final CacheStatsService cacheStatsService;

    // 所有存进 Redis 的三级缓存钥匙（Key），都必须带上这个前缀
    private static final String KEY_PREFIX = "chunk:";

    // 缓存存活的小时数，默认是 2 小时
    @Value("${rag.l3-chunk-cache-ttl-hours:2}")
    private int ttlHours;

    /**
     * 批量获取文档碎块的内容（就像去仓库领货，先去前台柜台看有没有现货，没有再去后方大仓库拿）
     *
     * @param chunkIds 需要获取的文档碎块的 ID 列表（比如：给我拿 1号、5号、8号 碎块）
     * @return 文档块列表（顺序和你要的顺序一样）
     */
    public List<DocumentChunk> getChunks(List<Long> chunkIds) {
        List<DocumentChunk> result = new ArrayList<>(chunkIds.size());
        List<Long> cacheMissIds = new ArrayList<>();
        int cacheHitCount = 0;

        // 1. 批量读取 Redis（一次 MGET 代替 N 次 GET，只需 1 次网络往返）
        List<String> keys = chunkIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toList();
        List<String> cachedValues = redisTemplate.opsForValue().multiGet(keys);

        for (int i = 0; i < chunkIds.size(); i++) {
            String cached = cachedValues != null ? cachedValues.get(i) : null;
            if (cached != null) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId(chunkIds.get(i));
                chunk.setContent(cached);
                result.add(chunk);
                cacheHitCount++;
            } else {
                result.add(null); // 占位，保持顺序
                cacheMissIds.add(chunkIds.get(i));
            }
        }

        // 2. 未命中的从数据库批量加载，并通过 Pipeline 批量回填 Redis
        if (!cacheMissIds.isEmpty()) {
            List<DocumentChunk> dbChunks = documentChunkMapper.selectBatchIds(cacheMissIds);

            Map<Long, DocumentChunk> dbMap = new HashMap<>();
            for (DocumentChunk c : dbChunks) {
                dbMap.put(c.getId(), c);
            }

            // 收集需要回填的 chunk，稍后一次性写入
            List<DocumentChunk> toCache = new ArrayList<>();
            for (int i = 0; i < chunkIds.size(); i++) {
                if (result.get(i) == null) {
                    DocumentChunk dbChunk = dbMap.get(chunkIds.get(i));
                    if (dbChunk != null) {
                        result.set(i, dbChunk);
                        toCache.add(dbChunk);
                    }
                }
            }

            // Pipeline 批量写入 Redis（一次网络往返写入所有 miss 的 chunk）
            if (!toCache.isEmpty()) {
                pipelineSetChunks(toCache);
            }
        }

        // 记录 L3 缓存命中统计
        if (cacheHitCount > 0) {
            cacheStatsService.recordL3Hit(cacheHitCount);
        }
        if (!cacheMissIds.isEmpty()) {
            cacheStatsService.recordL3Miss(cacheMissIds.size());
        }

        log.debug(“L3 文档缓存: 请求={}, 命中={}, 穿透={}”,
                chunkIds.size(), cacheHitCount, cacheMissIds.size());

        result.removeIf(Objects::isNull);
        return result;
    }

    /**
     * 通过 Redis Pipeline 批量写入多个 chunk 到缓存（一次网络往返代替 N 次 SET）
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
     * 按分块ID列表批量清除缓存（L3 缓存失效）
     * 当文档被删除或更新时，其对应的分块缓存应当被清除。
     *
     * @param chunkIds 需要清除的分块ID列表
     */
    public void invalidateByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = chunkIds.stream()
                    .map(id -> KEY_PREFIX + id)
                    .toList();
            redisTemplate.delete(keys);
            log.info("L3 文档缓存已清除: 清除数量={}", keys.size());
        } catch (Exception e) {
            log.warn("L3 文档缓存清除失败", e);
        }
    }

}
