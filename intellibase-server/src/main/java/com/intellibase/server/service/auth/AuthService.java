package com.intellibase.server.service.auth;

import com.intellibase.server.domain.dto.LoginRequest;
import com.intellibase.server.domain.dto.RegisterRequest;
import com.intellibase.server.domain.vo.LoginVO;
import com.intellibase.server.domain.vo.UserVO;

public interface AuthService {

    /**
     * 用户登录
     */
    LoginVO login(LoginRequest request);

    /**
     * 用户注册
     */
    UserVO register(RegisterRequest request);

}
