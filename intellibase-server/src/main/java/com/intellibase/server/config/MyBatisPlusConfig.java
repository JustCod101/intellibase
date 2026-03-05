package com.intellibase.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置类
 * 核心目的：注册 MyBatis-Plus 的各种插件（比如分页、乐观锁、防全表更新等）
 */
@Configuration // 告诉 Spring 这是一个配置类，启动时会自动加载
public class MyBatisPlusConfig {

    /**
     * 注入 MyBatis-Plus 的核心拦截器
     * * @return 包含各种内部插件的拦截器对象
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {

        // 1. 实例化一个拦截器“总管家”
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 2. 往“总管家”里添加一个具体的内部插件：分页插件（PaginationInnerInterceptor）
        // 参数 DbType.POSTGRE_SQL 非常关键：
        // 因为不同数据库的分页语法是不一样的（MySQL 用 LIMIT，Oracle 用 ROWNUM，PostgreSQL 用 LIMIT OFFSET）。
        // 这里告诉插件：“我的底层是 PostgreSQL 数据库，请按照 PostgreSQL 的语法帮我自动改写分页 SQL。”
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        // 如果你以后还需要别的插件（比如防止小白误写没 where 条件的 update 导致全表更新的插件），
        // 还可以继续在这里 addInnerInterceptor(...)

        // 3. 把配置好的拦截器返回给 Spring 容器管理
        return interceptor;
    }

}
