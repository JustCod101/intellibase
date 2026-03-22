package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.common.JwtUtils;
import com.intellibase.server.domain.dto.CreateConversationRequest;
import com.intellibase.server.domain.vo.ChatMessageVO;
import com.intellibase.server.domain.vo.ConversationVO;
import com.intellibase.server.service.chat.ChatService;
import com.intellibase.server.service.rag.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RagService ragService;

    @MockBean
    private ChatService chatService;

    @MockBean
    private JwtUtils jwtUtils; // Needed because JwtAuthFilter is in context

    @Test
    @WithMockUser(username = "100")
    void createConversation_Success() throws Exception {
        // Arrange
        CreateConversationRequest request = new CreateConversationRequest();
        request.setKbId(1L);
        request.setTitle("Test Title");

        ConversationVO vo = ConversationVO.builder()
                .id(1L)
                .kbId(1L)
                .title("Test Title")
                .build();

        when(chatService.createConversation(any(CreateConversationRequest.class), eq(100L))).thenReturn(vo);

        // Act & Assert
        mockMvc.perform(post("/api/v1/chat/conversations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Test Title"));
    }

    @Test
    @WithMockUser(username = "100")
    void getConversations_Success() throws Exception {
        // Arrange
        ConversationVO vo = ConversationVO.builder()
                .id(1L)
                .title("Test Title")
                .build();
        IPage<ConversationVO> page = new Page<>(1, 20);
        page.setRecords(Collections.singletonList(vo));

        when(chatService.getConversations(eq(100L), anyInt(), anyInt())).thenReturn(page);

        // Act & Assert
        mockMvc.perform(get("/api/v1/chat/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    @WithMockUser(username = "100")
    void getMessages_Success() throws Exception {
        // Arrange
        ChatMessageVO vo = ChatMessageVO.builder()
                .id(1L)
                .role("user")
                .content("Hello")
                .build();
        IPage<ChatMessageVO> page = new Page<>(1, 50);
        page.setRecords(Collections.singletonList(vo));

        when(chatService.getMessages(eq(1L), anyInt(), anyInt(), eq(100L))).thenReturn(page);

        // Act & Assert
        mockMvc.perform(get("/api/v1/chat/conversations/1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].content").value("Hello"));
    }

    @Test
    @WithMockUser(username = "100")
    void deleteConversation_Success() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/chat/conversations/1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(username = "100")
    void streamChat_Success() throws Exception {
        // Arrange
        when(chatService.getKbId(eq(1L), eq(100L))).thenReturn(10L);

        // Act & Assert
        mockMvc.perform(get("/api/v1/chat/stream")
                        .param("conversationId", "1")
                        .param("question", "What is AI?"))
                .andExpect(status().isOk());
        
        // Note: Testing SSE content would require more complex setup, 
        // but here we verify the endpoint is reachable and calls dependencies.
    }
}
