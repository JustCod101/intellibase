package com.intellibase.server.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    /** 分块 ID */
    private Long chunkId;

    /** 所属文档 ID */
    private Long docId;

    /** 余弦相似度分数（0~1） */
    private double score;

    /** 分块原始文本 */
    private String content;

    /** 文本摘要（用于返回给前端的引用来源） */
    private String snippet;

}
