package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellibase.server.domain.vo.RetrievalResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 【二级缓存】检索结果缓存服务 (L2 Retrieval Cache Service)
 * <p>
 * 缓存层级：L0 (Caffeine JVM本地) → L2 (Redis)
 * <p>
 * L0 本地缓存拦截在 Redis 之前，对于近期最热的查询可以做到零网络延迟返回。
 * L2 Redis 缓存作为跨实例共享的二级缓存，保证多实例部署时的缓存一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheStatsService cacheStatsService;

    private static final String KEY_PREFIX = "retrieval_cache:";

    @Value("${rag.l2-retrieval-cache-ttl-minutes:30}")
    private int ttlMinutes;

    @Value("${rag.l0-local-cache-ttl-minutes:10}")
    private int l0TtlMinutes;

    @Value("${rag.l0-local-cache-max-size:1000}")
    private int l0MaxSize;

    /** L0: JVM 进程内 Caffeine 本地缓存，Key 格式同 Redis Key */
    private Cache<String, List<RetrievalResult>> localCache;

    @PostConstruct
    public void init() {
        localCache = Caffeine.newBuilder()
                .maximumSize(l0MaxSize)
                .expireAfterWrite(l0TtlMinutes, TimeUnit.MINUTES)
                .build();
        log.info("L0 本地检索缓存已初始化: maxSize={}, ttl={}min", l0MaxSize, l0TtlMinutes);
    }

    /**
     * 尝试获取缓存的检索结果：L0 (Caffeine) → L2 (Redis)
     */
    public Optional<List<RetrievalResult>> tryGetCachedResults(String query, Long kbId) {
        String key = buildKey(query, kbId);

        // L0: 先查 JVM 本地缓存（零网络延迟）
        List<RetrievalResult> l0Result = localCache.getIfPresent(key);
        if (l0Result != null) {
            cacheStatsService.recordL0Hit();
            log.debug("L0 本地检索缓存命中: key={}", key);
            return Optional.of(l0Result);
        }
        cacheStatsService.recordL0Miss();

        // L2: 本地没有，查 Redis
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                List<RetrievalResult> results = objectMapper.readValue(
                        json, new TypeReference<List<RetrievalResult>>() {});
                // 回填 L0 本地缓存
                localCache.put(key, results);
                log.debug("L2 检索缓存命中: key={}", key);
                return Optional.of(results);
            }
        } catch (Exception e) {
            log.warn("L2 检索缓存读取失败: key={}", key, e);
        }
        return Optional.empty();
    }

    /**
     * 写入检索结果缓存：同时写入 L0 (Caffeine) 和 L2 (Redis)
     */
    public void cacheResults(String query, Long kbId, List<RetrievalResult> results) {
        String key = buildKey(query, kbId);

        // 写入 L0 本地缓存
        localCache.put(key, results);

        // 写入 L2 Redis
        try {
            String json = objectMapper.writeValueAsString(results);
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("L2 检索缓存已写入: key={}, TTL={}min", key, ttlMinutes);
        } catch (Exception e) {
            log.warn("L2 检索缓存写入失败: key={}", key, e);
        }
    }

    /**
     * 清除指定知识库的所有检索缓存（L0 + L2）
     */
    public void invalidateByKbId(Long kbId) {
        // L0: 清除本地缓存中属于该知识库的条目
        String prefix = KEY_PREFIX + kbId + ":";
        localCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));

        // L2: 清除 Redis
        try {
            String pattern = KEY_PREFIX + kbId + ":*";
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("L2 检索缓存已清除: kbId={}, 清除数量={}", kbId, keys.size());
            }
        } catch (Exception e) {
            log.warn("L2 检索缓存清除失败: kbId={}", kbId, e);
        }
    }

    private String buildKey(String query, Long kbId) {
        return KEY_PREFIX + kbId + ":" + sha256(query);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

}
