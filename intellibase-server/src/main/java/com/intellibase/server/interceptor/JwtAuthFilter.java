package com.intellibase.server.interceptor;

import com.intellibase.server.common.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * JWT 认证过滤器
 * 从请求头提取 Bearer Token，校验后将用户信息写入 SecurityContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 从 Header 中提取 Token
        String token = resolveToken(request);

        // 2. 如果 Token 存在，尝试解析并设置认证信息
        if (token != null) {
            try {
                Claims claims = jwtUtils.parseToken(token);

                String userId = claims.getSubject();
                String username = claims.get("username", String.class);
                String role = claims.get("role", String.class);
                Long tenantId = claims.get("tenantId", Long.class);

                // 构建 Spring Security 认证对象，authorities 使用 ROLE_ 前缀
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);

                // 将用户名和租户ID存入 details，供 TenantInterceptor 和业务层使用
                authentication.setDetails(Map.of(
                        "username", username,
                        "tenantId", tenantId != null ? tenantId : 0L
                ));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.debug("Token 校验失败: {}", e.getMessage());
                // Token 无效时不设置认证信息，后续由 Security 框架返回 401
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从 Authorization Header 中提取 Bearer Token
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

}
