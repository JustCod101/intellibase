package com.intellibase.server.domain.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文本分块
 */
@Data
@NoArgsConstructor
public class TextChunk implements Serializable {

    /** 块在文档中的序号（从0开始） */
    private int index;

    /** 块的原始文本内容 */
    private String content;

    /** Token 数量（近似值，按字符数 / 4 估算） */
    private int tokenCount;

    /** 分块附加元数据（JSON） */
    private String metadata;

    public TextChunk(int index, String content, int tokenCount) {
        this(index, content, tokenCount, null);
    }

    @Builder
    public TextChunk(int index, String content, int tokenCount, String metadata) {
        this.index = index;
        this.content = content;
        this.tokenCount = tokenCount;
        this.metadata = metadata;
    }
}
