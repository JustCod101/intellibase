package com.intellibase.server.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.vo.RetrievalResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RAG 编排服务 (RagService) 单元测试
 */
@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private SemanticCacheService semanticCacheService;
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private StreamingChatLanguageModel streamingChatModel;

    @InjectMocks
    private RagService ragService;

    @BeforeEach
    void setUp() {
        // 注入 Mock 的 LLM 模型
        ReflectionTestUtils.setField(ragService, "streamingChatModel", streamingChatModel);
        ReflectionTestUtils.setField(ragService, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("RAG问答 - 命中语义缓存直接返回")
    void streamChat_CacheHit() throws Exception {
        String question = "什么是 RAG？";
        Long kbId = 1L;
        float[] vector = new float[]{0.1f};
        SseEmitter emitter = mock(SseEmitter.class);

        when(embeddingService.embed(question)).thenReturn(vector);
        when(semanticCacheService.tryGetCachedAnswer(eq(question), eq(kbId), eq(vector)))
                .thenReturn(Optional.of("这是缓存的答案"));

        // 执行
        ragService.streamChat(question, kbId, 1001L, emitter);

        // 等待异步任务完成（此处由于是 Virtual Thread Executor，单元测试中可能需要特殊处理或改为同步逻辑进行测试）
        // 为了简化测试，我们可以直接调用 doStreamChat (通过反射或将 doStreamChat 改为 package-private)
        // 此处先尝试直接运行，若环境支持 Virtual Threads 则可。
        Thread.sleep(200); // 临时等待异步

        verify(emitter, atLeastOnce()).send(Collections.singleton(any()));
        verify(emitter).complete();
        verifyNoInteractions(retrievalService, streamingChatModel);
    }

    @Test
    @DisplayName("RAG问答 - 缓存未命中，执行完整流程")
    @SuppressWarnings("unchecked")
    void streamChat_CacheMiss_FullFlow() throws Exception {
        String question = "如何配置 MinIO？";
        Long kbId = 1L;
        float[] vector = new float[]{0.1f};
        SseEmitter emitter = mock(SseEmitter.class);

        // 1. Mock 依赖
        when(embeddingService.embed(question)).thenReturn(vector);
        when(semanticCacheService.tryGetCachedAnswer(any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        
        List<RetrievalResult> results = List.of(new RetrievalResult());
        when(retrievalService.retrieve(vector, kbId)).thenReturn(results);
        
        when(promptBuilder.buildSystemPrompt(any())).thenReturn("System Prompt");
        when(promptBuilder.buildUserMessage(any())).thenReturn("User Message");

        // 2. 模拟 LLM 流式输出行为
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("答案的第一个 Token");
            handler.onComplete(Response.from(AiMessage.from("完整答案")));
            return null;
        }).when(streamingChatModel).generate(anyList(), any(StreamingResponseHandler.class));

        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        // 3. 执行
        ragService.streamChat(question, kbId, 1001L, emitter);

        Thread.sleep(500); // 等待异步回调完成

        // 4. 验证流程
        verify(embeddingService).embed(question);
        verify(retrievalService).retrieve(vector, kbId);
        verify(streamingChatModel).generate(anyList(), any());
        verify(semanticCacheService).cacheAnswer(eq(question), anyString(), eq(kbId), eq(vector));
        verify(emitter, atLeastOnce()).send(Collections.singleton(any()));
        verify(emitter).complete();
    }
}
