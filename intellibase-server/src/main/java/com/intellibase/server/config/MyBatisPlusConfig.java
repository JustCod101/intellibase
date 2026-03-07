package com.intellibase.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.intellibase.server.interceptor.TenantInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 全局配置类
 * 注册多租户拦截器和分页插件
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 需要租户隔离的表（含 tenant_id 列的表）
     */
    private static final Set<String> TENANT_TABLES = Set.of(
            "sys_user",
            "knowledge_base"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 多租户插件（必须在分页插件之前添加）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                Long tenantId = TenantInterceptor.TenantContext.get();
                // 未登录场景（公开接口）返回 0，不会匹配任何数据
                return new LongValue(tenantId != null ? tenantId : 0L);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 只对包含 tenant_id 的表生效，其余表跳过
                return !TENANT_TABLES.contains(tableName);
            }
        }));

        // 2. 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        return interceptor;
    }

}
