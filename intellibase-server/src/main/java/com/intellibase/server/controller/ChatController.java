package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam Long conversationId,
                                 @RequestParam String question) {
        // TODO: RAG 检索 → Prompt 组装 → LLM 流式推理 → SSE 返回
        SseEmitter emitter = new SseEmitter(120_000L);
        return emitter;
    }

    @GetMapping("/conversations")
    public Result<?> conversations() {
        // TODO: 会话列表
        return Result.ok();
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<?> messages(@PathVariable Long id) {
        // TODO: 历史消息
        return Result.ok();
    }

}
