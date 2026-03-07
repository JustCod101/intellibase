package com.intellibase.server.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 多租户数据隔离拦截器
 * <p>
 * 职责：在每次请求进入 Controller 前，从 SecurityContext 中提取当前用户 ID 作为 tenant_id，
 * 存入 ThreadLocal（TenantContext）。MyBatis-Plus 的 TenantLineInnerInterceptor 会
 * 自动读取该值，为 SQL 追加 WHERE tenant_id = ? 条件。
 * <p>
 * 请求结束后自动清理 ThreadLocal，防止线程池复用导致的数据串租户。
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null && !"anonymousUser".equals(auth.getPrincipal())) {
            Long tenantId = Long.parseLong(auth.getPrincipal().toString());
            TenantContext.set(tenantId);
            log.debug("租户上下文已设置: tenantId={}", tenantId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    /**
     * 基于 ThreadLocal 的租户上下文，线程安全
     */
    public static class TenantContext {

        private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

        public static void set(Long tenantId) {
            CURRENT_TENANT.set(tenantId);
        }

        public static Long get() {
            return CURRENT_TENANT.get();
        }

        public static void clear() {
            CURRENT_TENANT.remove();
        }
    }

}
