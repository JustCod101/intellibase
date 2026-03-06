package com.intellibase.server.service.rag;

import com.intellibase.server.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存服务
 * <p>
 * 核心原理：
 * 传统的关键词缓存（如 Redis Key）要求输入完全一致。
 * 语义缓存则是比较两个问题的“语义相似度”。
 * 例如：“怎么请假？”和“请假流程是什么？”语义非常接近，可以共用同一个缓存答案。
 * 
 * 作用：大幅节省大模型调用成本，提升响应速度（秒回）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    // 缓存过期时间，默认 24 小时
    @Value("${rag.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // 语义匹配阈值，相似度超过 0.95 才认为可以复用缓存
    @Value("${rag.cache-similarity-threshold:0.95}")
    private double cacheThreshold;

    private final StringRedisTemplate redisTemplate;
    // 复用聊天记录表或专门的缓存表进行向量检索
    private final ChatMessageMapper chatMessageMapper;
    private final CacheStatsService cacheStatsService;

    /**
     * 尝试从缓存中获取答案（L1 语义缓存）
     *
     * @param question 用户当前提问
     * @param kbId     所属知识库
     * @param vector   提问的向量指纹
     */
    public Optional<String> tryGetCachedAnswer(String question, Long kbId, float[] vector) {
        String vectorStr = EmbeddingService.toVectorString(vector);

        String cachedAnswer = chatMessageMapper.findCachedAnswer(vectorStr, kbId, cacheThreshold);

        if (cachedAnswer != null) {
            cacheStatsService.recordL1Hit();
            return Optional.of(cachedAnswer);
        }
        cacheStatsService.recordL1Miss();
        return Optional.empty();
    }

    /**
     * 将回答存入缓存
     * 目前阶段直接存入数据库，供后续检索使用。
     * 未来可以结合 Redis 进一步提升热点问题的读取速度。
     *
     * @param question 问题原文
     * @param answer   大模型给出的高质量回答
     * @param kbId     知识库ID
     * @param vector   问题的向量指纹
     */
    public void cacheAnswer(String question, String answer, Long kbId, float[] vector) {
        try {
            // 语义缓存的关键在于：保存问题、答案以及问题的向量。
            // 这样下次检索时，是拿“新问题的向量”去匹配“旧问题的向量”。
            chatMessageMapper.insertCache(question, answer, kbId, EmbeddingService.toVectorString(vector));
            log.debug("已存入语义缓存: question={}", question);
        } catch (Exception e) {
            log.warn("写入语义缓存失败", e);
        }
    }

}
