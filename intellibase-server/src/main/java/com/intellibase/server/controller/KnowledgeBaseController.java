package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kb")
public class KnowledgeBaseController {

    @GetMapping
    public Result<?> list() {
        // TODO: 知识库列表（分页）
        return Result.ok();
    }

    @PostMapping
    public Result<?> create() {
        // TODO: 创建知识库
        return Result.ok();
    }

}
