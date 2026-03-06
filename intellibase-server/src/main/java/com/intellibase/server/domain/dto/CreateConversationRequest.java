package com.intellibase.server.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateConversationRequest {

    @NotNull(message = "知识库ID不能为空")
    private Long kbId;

    /** 会话标题（可选，为空时自动生成） */
    private String title;

}
