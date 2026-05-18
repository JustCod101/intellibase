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
import java.util.Set;

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

    // 缓存命中后的词面锚点校验：向量足够相似后，还要确认当前问题与历史问题共享足够关键词，降低假阳性。
    @Value("${rag.cache-sanity-check-enabled:true}")
    private boolean cacheSanityCheckEnabled;

    // 当前问题 tokens 中至少有多少比例能在历史问题中找到；默认 0.25 兼顾中文 bigram 与短问句。
    @Value("${rag.cache-sanity-min-token-overlap:0.25}")
    private double cacheSanityMinTokenOverlap;

    // 多取几个候选：如果最相似的一条词面校验失败，可以继续尝试下一条，而不是直接穿透。
    @Value("${rag.cache-sanity-candidate-limit:3}")
    private int cacheSanityCandidateLimit;

    // 使用专门的 SemanticCacheMapper 操作 semantic_cache 表
    private final SemanticCacheMapper semanticCacheMapper;
    // 用来统计"省了多少次大模型调用"，给老板看我们的优化成果
    private final CacheStatsService cacheStatsService;
    // 复用检索链路里的轻量分词器，做命中后的 lexical sanity check
    private final LexicalTokenizer lexicalTokenizer;

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

        // 去数据库里执行相似度搜索，找相似度大于阈值的老问题答案。
        // 开启 sanity check 时多取几个候选，避免 Top-1 向量假阳性直接污染答案。
        int limit = Math.max(1, cacheSanityCheckEnabled ? cacheSanityCandidateLimit : 1);
        List<SemanticCache> results = semanticCacheMapper.findSimilar(vectorStr, kbId, cacheThreshold, limit);

        // 如果找到了
        if (results != null && !results.isEmpty()) {
            for (SemanticCache hit : results) {
                if (!passesSanityCheck(question, hit.getQueryText())) {
                    log.debug("L1 语义缓存候选被 sanity check 拒绝: kbId={}, cacheId={}, question={}, cachedQuestion={}",
                            kbId, hit.getId(), question, hit.getQueryText());
                    continue;
                }
                // 命中计数 +1，方便后续分析哪些缓存最有价值
                semanticCacheMapper.incrementHitCount(hit.getId());
                // 记录一次"命中"，证明我们的缓存立功了！
                cacheStatsService.recordL1Hit();
                return Optional.of(hit.getResponseText());
            }
        }
        // 没找到相似的问题，记录一次"未命中"，老老实实去走后面的流程
        cacheStatsService.recordL1Miss();
        return Optional.empty();
    }

    /**
     * 向量缓存的二次校验。
     * <p>
     * 设计取舍：不再调用 LLM 做额外判定（避免缓存命中反而变慢变贵），而是用轻量词面锚点兜底。
     * 向量阈值负责"语义相似"，token overlap 负责过滤明显串题的假阳性。
     */
    private boolean passesSanityCheck(String question, String cachedQuestion) {
        if (!cacheSanityCheckEnabled) {
            return true;
        }

        List<String> queryTokens = lexicalTokenizer.tokenizeForMatch(question);
        Set<String> cachedTokens = lexicalTokenizer.tokenSet(cachedQuestion);
        if (queryTokens.isEmpty() || cachedTokens.isEmpty()) {
            return false;
        }

        long matched = queryTokens.stream()
                .filter(cachedTokens::contains)
                .count();
        double overlap = (double) matched / queryTokens.size();
        double requiredOverlap = Math.max(0.0, Math.min(1.0, cacheSanityMinTokenOverlap));
        return overlap >= requiredOverlap;
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
