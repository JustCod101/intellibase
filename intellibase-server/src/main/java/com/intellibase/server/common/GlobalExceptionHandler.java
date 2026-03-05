package com.intellibase.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 核心目的：统一拦截 Controller 层抛出的异常，封装成统一的 Result 格式返回，避免前端看到杂乱的错误页面。
 */
@Slf4j // 引入 Lombok 的日志记录器
@RestControllerAdvice // 相当于 @ControllerAdvice + @ResponseBody，说明这个类专门处理全局异常，且返回值会自动转成 JSON
public class GlobalExceptionHandler {

    /**
     * 处理“非法参数异常”（IllegalArgumentException）
     * 场景：通常是你自己用 Assert.notNull() 或者手动抛出该异常时触发。代表前端传来的参数不符合业务要求。
     */
    @ExceptionHandler(IllegalArgumentException.class) // 指定要拦截的异常类型
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 将 HTTP 状态码设置为 400（Bad Request），符合 RESTful 语义
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        // 1. 记录警告日志。因为是用户传参错误，不是系统本身坏了，所以用 warn 级别即可，不打印冗长的堆栈
        log.warn("参数错误: {}", e.getMessage());

        // 2. 返回统一格式的 Result 给前端，告诉他们具体的错误信息
        return Result.fail(400, e.getMessage());
    }

    /**
     * 处理“所有未知的顶级异常”（Exception）
     * 场景：系统的最后一道防线。用来捕获空指针（NullPointerException）、数据库断连、除以零等所有没有被专门处理的致命错误。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // 将 HTTP 状态码设置为 500（服务器内部错误）
    public Result<Void> handleException(Exception e) {
        // 1. 记录错误日志。这是真正的系统 Bug，必须用 error 级别，并且把异常对象 e 传进去，打印完整的堆栈信息方便排查
        log.error("服务器内部错误", e);

        // 2. 返回给前端统一的友好提示，绝对不要把 e.getMessage()（可能包含 SQL 语句或代码细节）直接丢给前端，防止安全风险
        return Result.fail(500, "服务器内部错误");
    }

}
