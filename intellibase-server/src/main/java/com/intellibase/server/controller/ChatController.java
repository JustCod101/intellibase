package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.common.Result;
import com.intellibase.server.domain.dto.CreateConversationRequest;
import com.intellibase.server.domain.vo.ChatMessageVO;
import com.intellibase.server.domain.vo.ConversationVO;
import com.intellibase.server.service.chat.ChatService;
import com.intellibase.server.service.rag.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;
    private final ChatService chatService;

    /**
     * 创建新会话
     */
    @PostMapping("/conversations")
    public Result<ConversationVO> createConversation(@Valid @RequestBody CreateConversationRequest request,
                                                     Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        ConversationVO vo = chatService.createConversation(request, userId);
        return Result.ok(vo);
    }

    /**
     * SSE 流式问答
     * 完整流程：语义缓存 → Embedding → pgvector 检索 → Prompt 组装 → LLM 流式推理 → 消息持久化
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam Long conversationId,
                                 @RequestParam String question,
                                 Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        Long kbId = chatService.getKbId(conversationId, userId);
        SseEmitter emitter = new SseEmitter(120_000L);
        ragService.streamChat(question, kbId, conversationId, emitter);
        return emitter;
    }

    /**
     * 获取当前用户的会话列表（分页）
     */
    @GetMapping("/conversations")
    public Result<IPage<ConversationVO>> conversations(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        IPage<ConversationVO> result = chatService.getConversations(userId, page, size);
        return Result.ok(result);
    }

    /**
     * 获取会话的历史消息（分页）
     */
    @GetMapping("/conversations/{id}/messages")
    public Result<IPage<ChatMessageVO>> messages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "50") Integer size,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        IPage<ChatMessageVO> result = chatService.getMessages(id, page, size, userId);
        return Result.ok(result);
    }

    /**
     * 删除会话（级联删除消息）
     */
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id,
                                           Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        chatService.deleteConversation(id, userId);
        return Result.ok();
    }

}
