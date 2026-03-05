package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import com.intellibase.server.domain.dto.LoginRequest;
import com.intellibase.server.domain.dto.RegisterRequest;
import com.intellibase.server.domain.vo.LoginVO;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.service.auth.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        LoginVO loginVO = authService.login(request);
        return Result.ok(loginVO);
    }

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        UserVO userVO = authService.register(request);
        return Result.ok(userVO);
    }

}
