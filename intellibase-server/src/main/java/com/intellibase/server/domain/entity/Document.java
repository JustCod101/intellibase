package com.intellibase.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document")
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
    private String metadata;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

}
