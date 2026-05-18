package com.intellibase.server.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.vo.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 【二级缓存】检索结果缓存服务 (L2 Retrieval Cache Service)
 * <p>
 * 缓存层级：L2 (Redis)
 * <p>
 * Redis 缓存作为跨实例共享的检索结果缓存，避免 L0 本地缓存导致多实例一致性和失效复杂度。
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

    @Value("${rag.hybrid.pipeline-version:1}")
    private int pipelineVersion;

    /**
     * 尝试获取缓存的检索结果：L2 (Redis)
     */
    public Optional<List<RetrievalResult>> tryGetCachedResults(String query, Long kbId, String configHash) {
        String key = buildKey(query, kbId, configHash);

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
     * 写入检索结果缓存：L2 (Redis)
     */
    public void cacheResults(String query, Long kbId, String configHash, List<RetrievalResult> results) {
        String key = buildKey(query, kbId, configHash);

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
     * 清除指定知识库的所有检索缓存（L2）
     */
    public void invalidateByKbId(Long kbId) {
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

    private String buildKey(String query, Long kbId, String configHash) {
        String effectiveConfigHash = StringUtils.hasText(configHash) ? configHash : "default";
        return KEY_PREFIX + kbId + ":" + pipelineVersion + ":" + effectiveConfigHash + ":" + sha256(query);
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
