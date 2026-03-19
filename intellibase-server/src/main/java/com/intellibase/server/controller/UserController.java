package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import com.intellibase.server.domain.dto.UpdateProfileRequest;
import com.intellibase.server.domain.entity.SysUser;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.mapper.UserMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/profile")
    public Result<UserVO> getProfile(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return Result.ok(toVO(user));
    }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                        Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 更新邮箱
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }

        // 修改密码
        if (request.getNewPassword() != null) {
            if (request.getOldPassword() == null) {
                throw new IllegalArgumentException("请输入原密码");
            }
            if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
                throw new IllegalArgumentException("原密码错误");
            }
            user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        }

        userMapper.updateById(user);
        return Result.ok(toVO(user));
    }

    private UserVO toVO(SysUser user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

}
