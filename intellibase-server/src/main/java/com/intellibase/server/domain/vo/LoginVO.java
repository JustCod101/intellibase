package com.intellibase.server.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String accessToken;

    private String tokenType;

    /** Token 有效期（秒） */
    private Long expiresIn;

}
