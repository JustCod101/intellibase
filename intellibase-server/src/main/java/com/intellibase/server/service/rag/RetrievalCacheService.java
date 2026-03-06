package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.vo.RetrievalResult;
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
 * L2 检索缓存服务
 * <p>
 * 缓存策略：将 query 的 SHA-256 哈希 + kbId 作为 Redis Key，
 * 检索结果序列化为 JSON 存储。
 * <p>
 * Key 格式: retrieval_cache:{kbId}:{queryHash}
 * TTL: 30 分钟
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "retrieval_cache:";

    @Value("${rag.l2-retrieval-cache-ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * 尝试从 Redis 获取缓存的检索结果
     */
    public Optional<List<RetrievalResult>> tryGetCachedResults(String query, Long kbId) {
        String key = buildKey(query, kbId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                List<RetrievalResult> results = objectMapper.readValue(
                        json, new TypeReference<List<RetrievalResult>>() {});
                log.debug("L2 检索缓存命中: key={}", key);
                return Optional.of(results);
            }
        } catch (Exception e) {
            log.warn("L2 检索缓存读取失败: key={}", key, e);
        }
        return Optional.empty();
    }

    /**
     * 将检索结果写入 Redis
     */
    public void cacheResults(String query, Long kbId, List<RetrievalResult> results) {
        String key = buildKey(query, kbId);
        try {
            String json = objectMapper.writeValueAsString(results);
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("L2 检索缓存已写入: key={}, TTL={}min", key, ttlMinutes);
        } catch (Exception e) {
            log.warn("L2 检索缓存写入失败: key={}", key, e);
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
            // fallback: 直接用 hashCode
            return String.valueOf(text.hashCode());
        }
    }

}
