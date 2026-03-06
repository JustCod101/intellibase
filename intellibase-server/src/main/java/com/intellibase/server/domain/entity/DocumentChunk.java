package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Long kbId;

    /** 块在文档中的序号 */
    private Integer chunkIndex;

    /** 原始文本 */
    private String content;

    /** Token 数量 */
    private Integer tokenCount;

    /**
     * 向量数据（pgvector vector(1536) 类型）
     * 在 MyBatis 层以字符串形式传递，格式为 "[0.1,0.2,...]"
     */
    @TableField(exist = false)
    private float[] embedding;

    /** JSONB — 页码、标题层级等 */
    private String metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

}
