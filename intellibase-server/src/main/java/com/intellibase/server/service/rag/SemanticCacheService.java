package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.SemanticCache;
import com.intellibase.server.mapper.SemanticCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 【一级缓存】语义缓存服务 (Semantic Cache Service)
 * <p>
 * 核心原理（大白话解释）：
 * 传统的缓存（比如 Redis）是"死脑筋"，你搜"请假流程"和"怎么请假"，它认为是两个完全不同的东西。
 * 但是大模型时代，我们可以把这两句话转换成"数学坐标"（向量），通过算它们的距离，发现它们的意思（语义）几乎一模一样。
 * <p>
 * 本类作用：
 * 充当大模型的"挡箭牌"。当用户提问时，先来这里查一下。如果以前有人问过类似的问题（相似度 > 95%），
 * 直接把以前大模型的回答丢给他。
 * 好处：省钱（不用调大模型接口）、速度极快（秒回，不用等大模型慢慢吐字）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    // 缓存过期时间，默认 24 小时（一天后，哪怕是一模一样的问题，也要重新问大模型一次，以防知识库更新了）
    @Value("${rag.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // 语义匹配阈值：相似度达到 0.95 (即95%) 才认为两个问题是一个意思，才可以复用答案。
    // 如果设置太低（比如0.5），那问"买车"和问"买房"可能会被当成同一个问题，导致回答错误。
    @Value("${rag.cache-similarity-threshold:0.95}")
    private double cacheThreshold;

    // 使用专门的 SemanticCacheMapper 操作 semantic_cache 表
    private final SemanticCacheMapper semanticCacheMapper;
    // 用来统计"省了多少次大模型调用"，给老板看我们的优化成果
    private final CacheStatsService cacheStatsService;

    /**
     * 步骤一：尝试白嫖之前的答案（L1 语义缓存检索）
     *
     * @param question 用户当前提问（如："今天天气怎么样？"）
     * @param kbId     所属知识库（不能串台，问公司A制度不能答公司B的）
     * @param vector   当前提问的"数学坐标"（已经通过大模型Embedding接口算好了的）
     * @return 如果有现成的答案，就包在一个 Optional 里返回；没有就返回 empty()。
     */
    public Optional<String> tryGetCachedAnswer(String question, Long kbId, float[] vector) {
        // 把浮点数数组转成数据库能认识的字符串格式："[0.1, -0.2, 0.5...]"
        String vectorStr = EmbeddingService.toVectorString(vector);

        // 去数据库里执行相似度搜索，找相似度大于阈值的老问题的答案（只取最相似的 1 条）
        List<SemanticCache> results = semanticCacheMapper.findSimilar(vectorStr, kbId, cacheThreshold, 1);

        // 如果找到了
        if (results != null && !results.isEmpty()) {
            SemanticCache hit = results.get(0);
            // 命中计数 +1，方便后续分析哪些缓存最有价值
            semanticCacheMapper.incrementHitCount(hit.getId());
            // 记录一次"命中"，证明我们的缓存立功了！
            cacheStatsService.recordL1Hit();
            return Optional.of(hit.getResponseText());
        }
        // 没找到相似的问题，记录一次"未命中"，老老实实去走后面的流程
        cacheStatsService.recordL1Miss();
        return Optional.empty();
    }

    /**
     * 步骤二：大模型回答完之后，把成果存起来
     *
     * 本次大模型辛苦思考得出的好答案，我们要把它和问题一起存进"小本本"，方便下次别人再问的时候直接用。
     *
     * @param question 问题原文（"今天天气怎样"）
     * @param answer   大模型给出的最终回答（"今天晴天，适合出行..."）
     * @param kbId     知识库ID
     * @param vector   问题的向量坐标（下次拿别人的坐标和这个坐标比对）
     */
    public void cacheAnswer(String question, String answer, Long kbId, float[] vector) {
        try {
            SemanticCache cache = new SemanticCache();
            cache.setKbId(kbId);
            cache.setQueryText(question);
            cache.setResponseText(answer);
            // 使用配置项 cacheTtlHours 计算过期时间，而非硬编码
            cache.setExpiresAt(OffsetDateTime.now().plusHours(cacheTtlHours));

            semanticCacheMapper.insertWithVector(cache, EmbeddingService.toVectorString(vector));
            log.debug("已存入一级语义缓存: question={}, ttl={}h", question, cacheTtlHours);
        } catch (Exception e) {
            // 存失败了也别抛出大异常影响主流程，默默打个日志就行
            log.warn("写入一级语义缓存失败", e);
        }
    }

    /**
     * 清除指定知识库的所有语义缓存（L1 缓存失效）
     * 当知识库文档发生变更时调用，确保不会返回基于旧文档生成的过时答案。
     *
     * @param kbId 知识库ID
     */
    public void invalidateByKbId(Long kbId) {
        try {
            semanticCacheMapper.deleteCacheByKbId(kbId);
            log.info("L1 语义缓存已清除: kbId={}", kbId);
        } catch (Exception e) {
            log.warn("L1 语义缓存清除失败: kbId={}", kbId, e);
        }
    }

}
