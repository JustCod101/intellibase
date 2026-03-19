package com.intellibase.server.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(min = 6, max = 128, message = "密码长度应在6-128之间")
    private String oldPassword;

    @Size(min = 6, max = 128, message = "密码长度应在6-128之间")
    private String newPassword;

}
