package com.intellibase.server.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.entity.SysUser;
import com.intellibase.server.domain.enums.RoleEnum;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserMapper userMapper;

    private static final Set<String> VALID_ROLES = Set.of(
            Constants.ROLE_ADMIN, Constants.ROLE_USER, Constants.ROLE_VIEWER);

    @Override
    public IPage<UserVO> listUsers(Long tenantId, int page, int size) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .orderByDesc(SysUser::getCreatedAt);

        Page<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        return result.convert(this::toVO);
    }

    @Override
    public void updateRole(Long userId, String role, Long tenantId) {
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("无效的角色: " + role);
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || !user.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("用户不存在");
        }

        user.setRole(role);
        userMapper.updateById(user);
    }

    @Override
    public void updateStatus(Long userId, Integer status, Long tenantId) {
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("无效的状态值，仅支持 0(禁用) 或 1(启用)");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null || !user.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("用户不存在");
        }

        user.setStatus(status);
        userMapper.updateById(user);
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
