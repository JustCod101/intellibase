package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @PostMapping("/login")
    public Result<?> login() {
        // TODO: 用户登录，返回 JWT Token
        return Result.ok();
    }

    @PostMapping("/register")
    public Result<?> register() {
        // TODO: 用户注册
        return Result.ok();
    }

}
