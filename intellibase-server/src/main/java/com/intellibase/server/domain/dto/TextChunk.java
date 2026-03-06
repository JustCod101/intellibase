package com.intellibase.server.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文本分块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextChunk implements Serializable {

    /** 块在文档中的序号（从0开始） */
    private int index;

    /** 块的原始文本内容 */
    private String content;

    /** Token 数量（近似值，按字符数 / 4 估算） */
    private int tokenCount;

}
