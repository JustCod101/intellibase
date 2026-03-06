package com.intellibase.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.intellibase.server.domain.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 更新文档解析状态
     */
    @Update("UPDATE document SET parse_status = #{status}, updated_at = NOW() WHERE id = #{docId}")
    int updateStatus(Long docId, String status);

    /**
     * 更新文档分块数量
     */
    @Update("UPDATE document SET chunk_count = #{chunkCount}, parse_status = #{status}, updated_at = NOW() WHERE id = #{docId}")
    int updateChunkCount(Long docId, Integer chunkCount, String status);

}
