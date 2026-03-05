package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kb/{kbId}/documents")
public class DocumentController {

    @PostMapping
    public Result<?> upload(@PathVariable Long kbId) {
        // TODO: 上传文档到 MinIO，发送解析消息到 RabbitMQ
        return Result.ok();
    }

    @GetMapping
    public Result<?> list(@PathVariable Long kbId) {
        // TODO: 文档列表（分页）
        return Result.ok();
    }

    @DeleteMapping("/{docId}")
    public Result<?> delete(@PathVariable Long kbId, @PathVariable Long docId) {
        // TODO: 删除文档（级联删除分块和向量）
        return Result.ok();
    }

}
