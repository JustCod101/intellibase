package com.intellibase.server.service.chat;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.domain.dto.CreateConversationRequest;
import com.intellibase.server.domain.vo.ChatMessageVO;
import com.intellibase.server.domain.vo.ConversationVO;
import com.intellibase.server.domain.vo.RetrievalResult;

import java.util.List;

public interface ChatService {

    /**
     * 创建新会话
     */
    ConversationVO createConversation(CreateConversationRequest request, Long userId);

    /**
     * 获取用户的会话列表（分页）
     */
    IPage<ConversationVO> getConversations(Long userId, Integer page, Integer size);

    /**
     * 获取会话的历史消息（分页），校验用户归属权
     */
    IPage<ChatMessageVO> getMessages(Long conversationId, Integer page, Integer size, Long userId);

    /**
     * 保存一轮对话（用户问题 + 助手回答）
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @param answer         助手回答
     * @param sources        引用来源 JSON
     * @param latencyMs      响应耗时
     */
    void saveMessage(Long conversationId, String question, String answer,
                     String sources, int latencyMs);

    /**
     * 获取会话所属的知识库ID，校验用户归属权
     */
    Long getKbId(Long conversationId, Long userId);

    /**
     * 删除会话（级联删除消息）
     */
    void deleteConversation(Long conversationId, Long userId);

}
