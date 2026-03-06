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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @InjectMocks
    private ChatServiceImpl chatService;

    private final String DEFAULT_MODEL = "gpt-4";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatService, "defaultModel", DEFAULT_MODEL);
    }

    @Test
    void createConversation_Success() {
        // Arrange
        CreateConversationRequest request = new CreateConversationRequest();
        request.setKbId(1L);
        request.setTitle("Test Conversation");
        Long userId = 100L;

        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            c.setId(1L);
            return 1;
        });

        // Act
        ConversationVO result = chatService.createConversation(request, userId);

        // Assert
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Test Conversation", result.getTitle());
        assertEquals(DEFAULT_MODEL, result.getModel());
        verify(conversationMapper, times(1)).insert(any(Conversation.class));
    }

    @Test
    void getConversations_Success() {
        // Arrange
        Long userId = 100L;
        Conversation conversation = new Conversation();
        conversation.setId(1L);
        conversation.setUserId(userId);
        conversation.setTitle("Test Conversation");

        Page<Conversation> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(conversation));

        when(conversationMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        // Act
        IPage<ConversationVO> result = chatService.getConversations(userId, 1, 10);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getRecords().get(0).getId());
        verify(conversationMapper, times(1)).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void getMessages_Success() {
        // Arrange
        Long conversationId = 1L;
        ChatMessage message = new ChatMessage();
        message.setId(1L);
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent("Hello");

        Page<ChatMessage> page = new Page<>(1, 50);
        page.setRecords(Collections.singletonList(message));

        when(chatMessageMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        // Act
        IPage<ChatMessageVO> result = chatService.getMessages(conversationId, 1, 50);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("user", result.getRecords().get(0).getRole());
        verify(chatMessageMapper, times(1)).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void saveMessage_Success() {
        // Arrange
        Long conversationId = 1L;
        String question = "What is AI?";
        String answer = "Artificial Intelligence is...";
        String sources = "[]";
        int latencyMs = 500;

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setTitle("新对话");

        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        // Act
        chatService.saveMessage(conversationId, question, answer, sources, latencyMs);

        // Assert
        verify(chatMessageMapper, times(2)).insert(any(ChatMessage.class));
        verify(conversationMapper, times(1)).updateById(any(Conversation.class));
        
        // Verify title update
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertEquals(question, captor.getValue().getTitle());
    }

    @Test
    void getKbId_Success() {
        // Arrange
        Long conversationId = 1L;
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setKbId(10L);

        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        // Act
        Long kbId = chatService.getKbId(conversationId);

        // Assert
        assertEquals(10L, kbId);
    }

    @Test
    void getKbId_NotFound_ThrowsException() {
        // Arrange
        Long conversationId = 1L;
        when(conversationMapper.selectById(conversationId)).thenReturn(null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> chatService.getKbId(conversationId));
    }

    @Test
    void deleteConversation_Success() {
        // Arrange
        Long conversationId = 1L;
        Long userId = 100L;
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUserId(userId);

        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        // Act
        chatService.deleteConversation(conversationId, userId);

        // Assert
        verify(conversationMapper, times(1)).deleteById(conversationId);
    }

    @Test
    void deleteConversation_WrongUser_ThrowsException() {
        // Arrange
        Long conversationId = 1L;
        Long userId = 100L;
        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUserId(999L); // Different user

        when(conversationMapper.selectById(conversationId)).thenReturn(conversation);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> chatService.deleteConversation(conversationId, userId));
    }
}
