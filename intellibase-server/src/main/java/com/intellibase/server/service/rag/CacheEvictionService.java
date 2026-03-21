package com.intellibase.server.service.rag;

import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 缓存失效服务（Cache Eviction Service）
 * <p>
 * 当知识库中的文档发生增删改操作时，统一协调清除三级缓存中的过期数据，
 * 防止用户看到基于旧文档生成的过时答案，尤其避免已删除的敏感文档内容仍通过缓存泄露。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheEvictionService {

    private final SemanticCacheService semanticCacheService;
    private final RetrievalCacheService retrievalCacheService;
    private final ChunkCacheService chunkCacheService;
    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 清除指定知识库的所有缓存（L0 + L1 + L2 + L3）
     * 适用场景：删除知识库
     *
     * @param kbId 知识库ID
     */
    public void evictAllByKbId(Long kbId) {
        log.info("开始清除知识库全部缓存: kbId={}", kbId);

        // L1：清除语义缓存（数据库中的问答对）
        semanticCacheService.invalidateByKbId(kbId);

        // L0 + L2：清除检索结果缓存（Caffeine 本地 + Redis）
        retrievalCacheService.invalidateByKbId(kbId);

        // L0 + L3：清除文档块缓存（Caffeine 本地 + Redis）
        try {
            List<Long> chunkIds = documentChunkMapper.selectChunkIdsByKbId(kbId);
            chunkCacheService.invalidateByChunkIds(chunkIds);
        } catch (Exception e) {
            log.warn("L3 缓存按知识库清除时查询 chunkId 失败: kbId={}", kbId, e);
        }

        log.info("知识库缓存清除完成: kbId={}", kbId);
    }

    /**
     * 清除指定文档关联的所有缓存（L0 + L1 + L2 + L3）
     * 适用场景：删除文档、文档重新上传/处理
     *
     * @param docId 文档ID
     * @param kbId  知识库ID
     */
    public void evictByDocument(Long docId, Long kbId) {
        log.info("开始清除文档关联缓存: docId={}, kbId={}", docId, kbId);

        // L1：清除该知识库的语义缓存（因为答案可能引用了该文档的内容）
        semanticCacheService.invalidateByKbId(kbId);

        // L0 + L2：清除该知识库的检索缓存（Caffeine 本地 + Redis）
        retrievalCacheService.invalidateByKbId(kbId);

        // L0 + L3：只清除该文档对应的分块缓存（Caffeine 本地 + Redis）
        try {
            List<Long> chunkIds = documentChunkMapper.selectChunkIdsByDocId(docId);
            chunkCacheService.invalidateByChunkIds(chunkIds);
        } catch (Exception e) {
            log.warn("L3 缓存按文档清除时查询 chunkId 失败: docId={}", docId, e);
        }

        log.info("文档关联缓存清除完成: docId={}, kbId={}", docId, kbId);
    }

}
