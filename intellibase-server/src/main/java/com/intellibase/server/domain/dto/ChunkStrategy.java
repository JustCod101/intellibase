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
}
