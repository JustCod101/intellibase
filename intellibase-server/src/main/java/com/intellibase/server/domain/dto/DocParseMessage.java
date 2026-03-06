package com.intellibase.server.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文档解析 MQ 消息体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocParseMessage implements Serializable {

    private Long docId;

    private Long kbId;

    /** MinIO 对象 Key */
    private String fileKey;

    private String fileType;

    /** 分块大小 */
    private Integer chunkSize;

    /** 分块重叠 */
    private Integer chunkOverlap;

}
