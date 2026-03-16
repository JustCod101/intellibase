package com.intellibase.server.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellibase.server.common.Constants;
import com.intellibase.server.common.JwtUtils;
import com.intellibase.server.domain.dto.LoginRequest;
import com.intellibase.server.domain.dto.RegisterRequest;
import com.intellibase.server.domain.entity.SysUser;
import com.intellibase.server.domain.vo.LoginVO;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Override
    public LoginVO login(LoginRequest request) {
        // 1. 根据用户名查询用户
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
        );
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 3. 检查账号状态
        if (user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已被禁用");
        }

        // 4. 生成 JWT Token
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        return LoginVO.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtUtils.getExpirationSeconds())
                .build();
    }

    @Override
    public UserVO register(RegisterRequest request) {
        // 1. 检查用户名是否已存在
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
        );
        if (count > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 2. 构建用户实体
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole(Constants.ROLE_USER);
        user.setTenantId(1L); // 默认租户
        user.setStatus(1);

        // 3. 写入数据库
        userMapper.insert(user);

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

}
