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
        // 这个箱子用来装最后找齐的所有货物
        List<DocumentChunk> result = new ArrayList<>(chunkIds.size());
        // 这个小本本用来记录：哪些货物前台（Redis）没有，需要去后方大仓库（数据库）拿
        List<Long> cacheMissIds = new ArrayList<>();
        int cacheHitCount = 0; // 记录一下我们在前台直接找到了多少件，年底好汇报业绩

        // 1. 先去前台柜台（Redis）挨个问问有没有货
        for (Long chunkId : chunkIds) {
            String cached = redisTemplate.opsForValue().get(KEY_PREFIX + chunkId);
            if (cached != null) {
                // 运气好，前台有货（命中）！
                // 因为为了省内存，Redis 里只存了文字内容，所以我们这里临时拼装一个对象
                DocumentChunk chunk = new DocumentChunk();
                chunk.setId(chunkId);
                chunk.setContent(cached);
                result.add(chunk); // 把找到的货放进大箱子里
                cacheHitCount++;
            } else {
                // 没运气，前台没货（未命中）
                result.add(null); // 先在大箱子里占个空位，免得待会顺序乱了
                cacheMissIds.add(chunkId); // 记在小本本上，待会一起去后方大仓库拿
            }
        }

        // 2. 如果小本本上记了需要去大仓库拿的货（说明前台没找齐）
        if (!cacheMissIds.isEmpty()) {
            // 开个卡车，把小本本上的货一次性从大仓库（底层数据库）拉回来
            List<DocumentChunk> dbChunks = documentChunkMapper.selectBatchIds(cacheMissIds);

            // 为了方便分发，把拉回来的货摆在架子上，按 ID 贴上标签
            java.util.Map<Long, DocumentChunk> dbMap = new java.util.HashMap<>();
            for (DocumentChunk c : dbChunks) {
                dbMap.put(c.getId(), c);
            }

            // 开始把后方拿回来的货，填补到刚才大箱子里的空位上
            for (int i = 0; i < chunkIds.size(); i++) {
                if (result.get(i) == null) { // 找到刚才留的空位
                    DocumentChunk dbChunk = dbMap.get(chunkIds.get(i));
                    if (dbChunk != null) {
                        result.set(i, dbChunk); // 把货放进去
                        // 最重要的一步：顺手把这件货放到前台（Redis）一份！
                        // 这样下次别人再要，前台就直接有现货了（回填缓存）
                        cacheChunk(dbChunk);
                    }
                }
            }
        }

        // 打印一条内部日志，记录一下这次取货的战况
        log.debug("L3 文档缓存: 请求={}, 命中={}, 穿透={}",
                chunkIds.size(), cacheHitCount, cacheMissIds.size());

        // 过滤掉那些既不在前台，也不在大仓库里的“幽灵货物”（正常情况不会发生，写了防患于未然）
        result.removeIf(java.util.Objects::isNull);
        return result; // 满载而归
    }

    /**
     * 把单独一个文档碎块摆到前台（写入 Redis 缓存）
     */
    public void cacheChunk(DocumentChunk chunk) {
        try {
            // 放进 Redis，并定个闹钟（默认2小时后自动扔掉）
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + chunk.getId(),
                    chunk.getContent(),
                    ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            // 放失败了也别影响主流程，默默记下这笔烂账
            log.warn("L3 文档缓存写入失败: chunkId={}", chunk.getId(), e);
        }
    }

    /**
     * 批量预热缓存（在检索出匹配的文档片段后，趁热打铁把它们都摆到前台去）
     */
    public void warmUp(List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            cacheChunk(chunk); // 挨个调用上面的方法摆货
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
