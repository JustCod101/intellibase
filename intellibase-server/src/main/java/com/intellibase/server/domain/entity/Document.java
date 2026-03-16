package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.intellibase.server.config.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "document", autoResultMap = true)
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String title;

    /** MinIO 对象 Key */
    private String fileKey;

    /** pdf, docx, md, txt */
    private String fileType;

    private Long fileSize;

    /** SHA-256 去重 */
    private String contentHash;

    /** PENDING / PARSING / EMBEDDING / COMPLETED / FAILED */
    private String parseStatus;

    private Integer chunkCount;

    /** JSONB — 自定义元数据（作者、版本等） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadata;

    private Long createdBy;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;

}
