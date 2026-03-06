package com.intellibase.server.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 向量化批次 MQ 消息体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbedBatchMessage implements Serializable {

    private Long docId;

    private Long kbId;

    /** 该批次是否为最后一批（用于最终状态更新） */
    private boolean lastBatch;

    /** 文档总分块数（仅 lastBatch=true 时有意义） */
    private int totalChunks;

    /** 本批次的文本块列表 */
    private List<TextChunk> chunks;

}
