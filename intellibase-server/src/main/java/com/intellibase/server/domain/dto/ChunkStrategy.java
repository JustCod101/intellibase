package com.intellibase.server.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = ChunkStrategyDeserializer.class)
public class ChunkStrategy implements Serializable {

    private Integer version;

    private String type;

    private Integer size;

    private Integer overlap;

    private Integer minSize;

    private Boolean normalizeWhitespace;

    /** 是否启用父子分块：子块用于检索，父块上下文用于生成。 */
    private Boolean parentChildEnabled;

    /** 父块目标字符数，建议约 1024~2048 token 对应的字符窗口。 */
    private Integer parentSize;

    /** 子块目标字符数，建议约 256~512 token 对应的字符窗口。 */
    private Integer childSize;

    /** 子块重叠字符数。 */
    private Integer childOverlap;
}
