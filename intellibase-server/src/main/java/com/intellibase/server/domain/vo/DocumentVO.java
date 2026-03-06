package com.intellibase.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVO {

    private Long id;

    private Long kbId;

    private String title;

    private String fileType;

    private Long fileSize;

    private String parseStatus;

    private Integer chunkCount;

    /** 原始 JSON 输出，不做二次序列化 */
    @JsonRawValue
    private String metadata;

    private LocalDateTime createdAt;

}
