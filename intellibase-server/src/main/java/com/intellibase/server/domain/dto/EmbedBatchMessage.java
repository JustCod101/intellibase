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

    /** 消息唯一标识（UUID），用于消费端幂等去重 */
    private String messageId;

    private Long docId;

    private Long kbId;

    /** @deprecated 保留兼容，完成判断已改用 Redis 原子计数器 */
    private boolean lastBatch;

    /** 文档总分块数 */
    private int totalChunks;

    /** 该文档的总批次数（每个批次消息都携带，用于 Redis 原子计数比对） */
    private int totalBatches;

    /** 本批次的文本块列表 */
    private List<TextChunk> chunks;

}
