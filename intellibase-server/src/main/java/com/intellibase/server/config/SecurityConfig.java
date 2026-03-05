package com.intellibase.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 全局安全配置类
 * 核心目的：配置哪些接口需要登录才能访问，哪些接口可以白嫖（免密访问），以及系统的认证策略。
 */
@Configuration
@EnableWebSecurity // 开启 Spring Security 的 Web 安全支持，相当于让保安正式上岗
public class SecurityConfig {

    /**
     * 配置安全拦截链（SecurityFilterChain）
     * 所有的 HTTP 请求进出你的系统，都必须经过这条“安检通道”。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. 关闭 CSRF（跨站请求伪造）防护
                // 通俗理解：CSRF 防护主要是为了防止浏览器中 Cookie 被盗用。
                // 因为我们现在做的是前后端分离的 REST API，通常用 Token（比如 JWT）而不是 Cookie 来认证，
                // 所以不需要这个防护，关掉它可以避免很多跨域和请求被拦截的麻烦。
                .csrf(AbstractHttpConfigurer::disable)

                // 2. 设置 Session 管理策略为：无状态（STATELESS）
                // 通俗理解：让保安变成“失忆症患者”。他绝不记住任何人的脸（不创建 Session）。
                // 你每次进门（发请求），都必须重新出示你的工牌（携带 JWT Token）。
                // 这种配置是微服务和前后端分离架构的标配，能大大减轻服务器的内存压力。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. 配置 HTTP 请求的授权规则（谁能进，谁不能进）
                .authorizeHttpRequests(auth -> auth
                        // 3.1 配置“白名单”（大堂、公共厕所，谁都能进）
                        .requestMatchers(
                                "/swagger-ui/**",   // Swagger 接口文档页面
                                "/v3/api-docs/**",  // Swagger 接口文档的 JSON 数据
                                "/actuator/**"      // Spring Boot 的健康检查和监控端点
                        ).permitAll() // permitAll() 表示这些路径完全放行，不需要登录

                        // 3.2 其他所有请求的规则（内部办公区）
                        // anyRequest() 表示除了上面配的白名单之外的所有请求
                        // authenticated() 表示必须是“认证过的”（也就是必须成功登录，出示了有效 Token）才能访问
                        .anyRequest().authenticated()
                );

        // 4. 按照上述规则，把安检通道构建出来并交给 Spring
        return http.build();
    }

}
