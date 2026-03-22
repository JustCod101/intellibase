package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.common.Result;
import com.intellibase.server.domain.dto.UpdateRoleRequest;
import com.intellibase.server.domain.dto.UpdateStatusRequest;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.service.admin.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    /**
     * 获取同租户下的用户列表（分页）
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        Long tenantId = extractTenantId(authentication);
        return Result.ok(adminService.listUsers(tenantId, page, size));
    }

    /**
     * 变更用户角色
     */
    @PutMapping("/users/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id,
                                   @Valid @RequestBody UpdateRoleRequest request,
                                   Authentication authentication) {
        Long tenantId = extractTenantId(authentication);
        adminService.updateRole(id, request.getRole(), tenantId);
        return Result.ok();
    }

    /**
     * 启用/禁用用户
     */
    @PutMapping("/users/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody UpdateStatusRequest request,
                                     Authentication authentication) {
        Long tenantId = extractTenantId(authentication);
        adminService.updateStatus(id, request.getStatus(), tenantId);
        return Result.ok();
    }

    @SuppressWarnings("unchecked")
    private Long extractTenantId(Authentication authentication) {
        Map<String, Object> details = (Map<String, Object>) authentication.getDetails();
        return (Long) details.get("tenantId");
    }
}
