package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 语义缓存查询：从 semantic_cache 表按向量相似度查找已缓存的回答
     */
    String findCachedAnswer(@Param("queryEmbedding") String queryEmbedding,
                            @Param("kbId") Long kbId,
                            @Param("threshold") double threshold);

    /**
     * 语义缓存写入：将问答对写入 semantic_cache 表
     */
    void insertCache(@Param("queryText") String queryText,
                     @Param("responseText") String responseText,
                     @Param("kbId") Long kbId,
                     @Param("embedding") String embedding);

    /**
     * 按知识库ID清除语义缓存
     */
    void deleteCacheByKbId(@Param("kbId") Long kbId);

}
