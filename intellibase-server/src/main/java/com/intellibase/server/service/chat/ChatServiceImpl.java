package com.intellibase.server.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intellibase.server.domain.dto.CreateConversationRequest;
import com.intellibase.server.domain.entity.ChatMessage;
import com.intellibase.server.domain.entity.Conversation;
import com.intellibase.server.domain.vo.ChatMessageVO;
import com.intellibase.server.domain.vo.ConversationVO;
import com.intellibase.server.mapper.ChatMessageMapper;
import com.intellibase.server.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Value("${llm.model-name}")
    private String defaultModel;

    @Override
    public ConversationVO createConversation(CreateConversationRequest request, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setKbId(request.getKbId());
        conversation.setTitle(request.getTitle() != null ? request.getTitle() : "新对话");
        conversation.setModel(defaultModel);
        conversation.setConfig("{\"temperature\":0.7,\"topK\":5}");

        conversationMapper.insert(conversation);
        log.info("创建会话: conversationId={}, kbId={}", conversation.getId(), request.getKbId());

        return toConversationVO(conversation);
    }

    @Override
    public IPage<ConversationVO> getConversations(Long userId, Integer page, Integer size) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getUpdatedAt);

        Page<Conversation> result = conversationMapper.selectPage(new Page<>(page, size), wrapper);
        return result.convert(this::toConversationVO);
    }

    @Override
    public IPage<ChatMessageVO> getMessages(Long conversationId, Integer page, Integer size) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt);

        Page<ChatMessage> result = chatMessageMapper.selectPage(new Page<>(page, size), wrapper);
        return result.convert(this::toMessageVO);
    }

    @Override
    @Transactional
    public void saveMessage(Long conversationId, String question, String answer,
                            String sources, int latencyMs) {
        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(question);
        chatMessageMapper.insert(userMsg);

        // 保存助手消息
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(answer);
        assistantMsg.setSources(sources);
        assistantMsg.setLatencyMs(latencyMs);
        chatMessageMapper.insert(assistantMsg);

        // 用第一条用户消息自动更新会话标题
        updateTitleIfNeeded(conversationId, question);

        log.debug("对话已保存: conversationId={}, latencyMs={}", conversationId, latencyMs);
    }

    @Override
    public Long getKbId(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }
        return conversation.getKbId();
    }

    @Override
    public void deleteConversation(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在");
        }
        // chat_message 通过外键 ON DELETE CASCADE 级联删除
        conversationMapper.deleteById(conversationId);
        log.info("会话已删除: conversationId={}", conversationId);
    }

    // ======================== 私有方法 ========================

    /**
     * 如果会话标题仍为默认值，用第一条消息的前 30 字作为标题
     */
    private void updateTitleIfNeeded(Long conversationId, String question) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null && "新对话".equals(conversation.getTitle())) {
            String title = question.length() > 30 ? question.substring(0, 30) + "..." : question;
            conversation.setTitle(title);
            conversationMapper.updateById(conversation);
        }
    }

    private ConversationVO toConversationVO(Conversation c) {
        return ConversationVO.builder()
                .id(c.getId())
                .kbId(c.getKbId())
                .title(c.getTitle())
                .model(c.getModel())
                .config(c.getConfig())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private ChatMessageVO toMessageVO(ChatMessage m) {
        return ChatMessageVO.builder()
                .id(m.getId())
                .conversationId(m.getConversationId())
                .role(m.getRole())
                .content(m.getContent())
                .tokenUsage(m.getTokenUsage())
                .sources(m.getSources())
                .latencyMs(m.getLatencyMs())
                .createdAt(m.getCreatedAt())
                .build();
    }

}
