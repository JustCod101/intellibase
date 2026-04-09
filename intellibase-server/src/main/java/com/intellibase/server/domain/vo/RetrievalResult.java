package com.intellibase.server.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 混合检索结果
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

    /** 最终排序分数（保持兼容旧字段） */
    private double score;

    /** 稠密召回分数 */
    private Double denseScore;

    /** 稀疏召回分数 */
    private Double sparseScore;

    /** 融合得分 */
    private Double fusedScore;

    /** 重排得分 */
    private Double rerankScore;

    /** 命中类型：DENSE / SPARSE / HYBRID */
    private String matchType;

    /** 分块原始文本 */
    private String content;

    /** 文本摘要（用于返回给前端的引用来源） */
    private String snippet;

}
