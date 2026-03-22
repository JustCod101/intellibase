package com.intellibase.server.service.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.domain.vo.UserVO;

public interface AdminService {

    /**
     * 查询同租户下的用户列表（分页）
     */
    IPage<UserVO> listUsers(Long tenantId, int page, int size);

    /**
     * 变更用户角色（需属于同一租户）
     */
    void updateRole(Long userId, String role, Long tenantId);

    /**
     * 启用/禁用用户（需属于同一租户）
     */
    void updateStatus(Long userId, Integer status, Long tenantId);
}
