package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.SemanticCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SemanticCacheMapper extends BaseMapper<SemanticCache> {

    /**
     * 向量相似度查找缓存
     */
    List<SemanticCache> findSimilar(@Param("queryEmbedding") String queryEmbedding,
                                    @Param("kbId") Long kbId,
                                    @Param("threshold") double threshold,
                                    @Param("limit") int limit);

    /**
     * 插入缓存（含向量）
     */
    void insertWithVector(@Param("cache") SemanticCache cache,
                          @Param("embedding") String embedding);

    /**
     * 命中计数 +1
     */
    void incrementHitCount(@Param("id") Long id);

}
