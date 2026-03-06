package com.intellibase.server.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateKbRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;

    private String embeddingModel;

    private String chunkStrategy;

}
