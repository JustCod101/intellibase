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
 * 【二级缓存】检索结果缓存服务 (L2 Retrieval Cache Service)
 * <p>
 * 核心原理（大白话解释）：
 * 当第一级缓存（语义缓存）没命中时，我们需要去庞大的知识库里搜索相关的文档片段。
 * 这个搜索过程涉及到数据库的向量距离计算，非常耗费性能和时间。
 * 如果张三刚搜完“公司报销制度”对应的文档片段，李四紧接着也搜“公司报销制度”，
 * 我们就没必要再去数据库里辛苦找一遍了，直接把张三刚才找出来的文档片段给李四用就行。
 * <p>
 * 缓存策略：
 * 怎么识别张三和李四搜的是同一个东西呢？
 * 我们把用户搜索的文字（比如"公司报销制度"）用 SHA-256 加密成一段唯一的字符串（哈希值）。
 * 再拼上知识库的ID（防止串台），作为 Redis 的钥匙（Key）。
 * 对应的锁里的东西（Value），就是当时从数据库里千辛万苦搜出来的文档片段（转成了JSON文本）。
 * <p>
 * Redis Key 格式长这样: retrieval_cache:{知识库ID}:{用户提问的哈希值}
 * TTL(存活时间): 默认 30 分钟。半小时后自动销毁，保证知识库更新后能搜出新内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCacheService {

    // 用来操作 Redis 数据库的工具人
    private final StringRedisTemplate redisTemplate;
    // 用来把 Java 对象变成 JSON 文本（存入Redis），或者把 JSON 文本变回 Java 对象（从Redis取出）的工具人
    private final ObjectMapper objectMapper;

    // 所有存进 Redis 的二级缓存钥匙（Key），都必须带上这个前缀，方便管理和清理
    private static final String KEY_PREFIX = "retrieval_cache:";

    // 缓存存活的分钟数，默认是 30 分钟
    @Value("${rag.l2-retrieval-cache-ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * 尝试去 Redis 里找找看，有没有别人刚搜过的现成的文档片段。
     *
     * @param query 用户提问原文
     * @param kbId  知识库ID
     * @return 如果运气好找到了，就返回组装好的文档片段列表；没找到就返回空。
     */
    public Optional<List<RetrievalResult>> tryGetCachedResults(String query, Long kbId) {
        // 先根据提问和知识库ID，打造出一把专用的 Redis 钥匙
        String key = buildKey(query, kbId);
        try {
            // 用这把钥匙去 Redis 里面开锁，看看有没有对应的 JSON 文本
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                // 如果里面有货！把干巴巴的 JSON 文本，复原成有血有肉的 Java 对象列表
                List<RetrievalResult> results = objectMapper.readValue(
                        json, new TypeReference<List<RetrievalResult>>() {});
                log.debug("L2 检索缓存命中: key={}", key); // 记录一下立功了
                return Optional.of(results);
            }
        } catch (Exception e) {
            // 如果 Redis 连不上或者 JSON 转换失败，别报错罢工，假装没缓存就行了
            log.warn("L2 检索缓存读取失败: key={}", key, e);
        }
        return Optional.empty(); // 啥也没找到
    }

    /**
     * 我们刚从底层数据库里辛辛苦苦查出来的一批文档片段，赶快放进 Redis 里存起来！
     * 这样后面的人再问一样的问题，就可以直接从上面那个方法白嫖了。
     *
     * @param query   当前用户的提问原文
     * @param kbId    知识库ID
     * @param results 刚从数据库里查出来的文档片段列表
     */
    public void cacheResults(String query, Long kbId, List<RetrievalResult> results) {
        // 同样，先打造一把专用的 Redis 钥匙
        String key = buildKey(query, kbId);
        try {
            // 把活生生的 Java 对象列表，压缩成干巴巴的 JSON 文本字符串
            String json = objectMapper.writeValueAsString(results);
            // 连同钥匙和货物一起放进 Redis 仓库，并定个闹钟（默认30分钟后自动丢弃）
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("L2 检索缓存已写入: key={}, TTL={}min", key, ttlMinutes);
        } catch (Exception e) {
            // 存失败了也别慌，不影响主流程给用户发答案
            log.warn("L2 检索缓存写入失败: key={}", key, e);
        }
    }

    /**
     * 清除指定知识库的所有检索缓存（L2 缓存失效）
     * 使用 Redis SCAN 命令按前缀匹配并删除，避免 KEYS 命令阻塞 Redis。
     *
     * @param kbId 知识库ID
     */
    public void invalidateByKbId(Long kbId) {
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

    /**
     * 专门负责打造 Redis 钥匙（Key）的工厂方法
     * 格式： retrieval_cache:知识库ID:问题原文的SHA256哈希
     */
    private String buildKey(String query, Long kbId) {
        return KEY_PREFIX + kbId + ":" + sha256(query);
    }

    /**
     * 一个能把任意长短的句子，变成固定长度的一串乱码（哈希值）的神奇方法。
     * 比如不管你问多长，都会变成类似 "a3f8c2...9b" 这样。
     * 这样作为 Redis 的 Key 就不会太长，也不会因为包含特殊字符导致 Redis 报错。
     */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // 万一服务器太古老不支持 SHA-256（基本不可能），就随便拿个 Java 自带的哈希码凑合用
            return String.valueOf(text.hashCode());
        }
    }

}
