package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Update("UPDATE knowledge_base SET doc_count = doc_count + #{delta} WHERE id = #{kbId}")
    int incrementDocCount(Long kbId, int delta);

}
