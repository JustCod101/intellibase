package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * L3 文档缓存服务
 * <p>
 * 缓存策略：将热点文档块的 content 按 chunkId 缓存到 Redis，减少数据库读取。
 * Redis 配置了 maxmemory-policy allkeys-lru，内存满时自动淘汰最近最少使用的 Key。
 * <p>
 * Key 格式: chunk:{chunkId}
 * TTL: 2 小时
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkCacheService {

    private final StringRedisTemplate redisTemplate;
    private final DocumentChunkMapper documentChunkMapper;

    private static final String KEY_PREFIX = "chunk:";

    @Value("${rag.l3-chunk-cache-ttl-hours:2}")
    private int ttlHours;

    /**
     * 批量获取文档块内容（优先从 Redis 读取，未命中的从 DB 加载并回填缓存）
     *
     * @param chunkIds 需要获取的 chunk ID 列表
     * @return 文档块列表（顺序与输入一致）
     */
    public List<DocumentChunk> getChunks(List<Long> chunkIds) {
        List<DocumentChunk> result = new ArrayList<>(chunkIds.size());
        List<Long> cacheMissIds = new ArrayList<>();
        int cacheHitCount = 0;

        // 1. 逐个从 Redis 获取
        for (Long chunkId : chunkIds) {
            String cached = redisTemplate.opsForValue().get(KEY_PREFIX + chunkId);
            if (cached != null) {
                // 命中：从缓存中还原 chunk（缓存只存 content，其余字段后续由调用方补充）
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId(chunkId);
                chunk.setContent(cached);
                result.add(chunk);
                cacheHitCount++;
            } else {
                // 未命中：记录 ID，稍后批量从 DB 加载
                result.add(null); // 占位
                cacheMissIds.add(chunkId);
            }
        }

        // 2. 批量从 DB 加载未命中的 chunk
        if (!cacheMissIds.isEmpty()) {
            List<DocumentChunk> dbChunks = documentChunkMapper.selectBatchIds(cacheMissIds);

            // 建立 ID -> chunk 的映射方便回填
            java.util.Map<Long, DocumentChunk> dbMap = new java.util.HashMap<>();
            for (DocumentChunk c : dbChunks) {
                dbMap.put(c.getId(), c);
            }

            // 回填结果列表 + 写入 Redis 缓存
            for (int i = 0; i < chunkIds.size(); i++) {
                if (result.get(i) == null) {
                    DocumentChunk dbChunk = dbMap.get(chunkIds.get(i));
                    if (dbChunk != null) {
                        result.set(i, dbChunk);
                        // 写入 L3 缓存
                        cacheChunk(dbChunk);
                    }
                }
            }
        }

        log.debug("L3 文档缓存: 请求={}, 命中={}, 穿透={}",
                chunkIds.size(), cacheHitCount, cacheMissIds.size());

        // 过滤掉 null（理论上不应存在，防御性编程）
        result.removeIf(java.util.Objects::isNull);
        return result;
    }

    /**
     * 将单个文档块写入 Redis 缓存
     */
    public void cacheChunk(DocumentChunk chunk) {
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + chunk.getId(),
                    chunk.getContent(),
                    ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("L3 文档缓存写入失败: chunkId={}", chunk.getId(), e);
        }
    }

    /**
     * 批量预热缓存（在检索命中后主动缓存热点 chunk）
     */
    public void warmUp(List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            cacheChunk(chunk);
        }
    }

}
