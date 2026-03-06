package com.intellibase.server.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.domain.vo.RetrievalResult;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * RAG (Retrieval-Augmented Generation) 编排引擎
 * <p>
 * 它是整个问答系统的"大脑"，负责协调各个组件完成以下流程：
 * 1. 向量化：将用户的问题转为一串数字（向量）。
 * 2. 语义缓存：看看以前有没有人问过类似的问题，如果有直接回。
 * 3. 检索：去数据库里找最相关的文档片段。
 * 4. 组装：把文档片段和问题拼成一个给大模型的指令（Prompt）。
 * 5. 生成：调用大模型（LLM）流式输出答案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final EmbeddingService embeddingService;
    private final SemanticCacheService semanticCacheService;
    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final com.intellibase.server.service.chat.ChatService chatService;
    private final ObjectMapper objectMapper;

    // 从配置文件中读取大模型的各种配置
    @Value("${llm.api-key}")
    private String llmApiKey;

    @Value("${llm.base-url}")
    private String llmBaseUrl;

    @Value("${llm.model-name}")
    private String llmModelName;

    @Value("${llm.temperature}")
    private double temperature;

    @Value("${llm.max-tokens}")
    private int maxTokens;

    // LangChain4j 提供的流式对话模型接口
    private StreamingChatLanguageModel streamingChatModel;

    /**
     * 任务执行器（线程池）
     * 
     * 由于当前环境使用的是 Java 17，改用标准的可缓存线程池 (CachedThreadPool)。
     * RAG 流程中包含大量的网络等待，使用多线程可以同时处理多个用户的提问。
     */
    private final Executor taskExecutor = Executors.newCachedThreadPool();

    /**
     * 初始化方法：在 Spring 项目启动时，根据配置创建大模型连接客户端
     */
    @PostConstruct
    public void init() {
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(llmApiKey)
                .baseUrl(llmBaseUrl)
                .modelName(llmModelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        log.info("RagService 初始化完成: 使用模型={}", llmModelName);
    }

    /**
     * 执行 RAG 流式问答
     *
     * @param question       用户问题
     * @param kbId           知识库ID
     * @param conversationId 会话ID（用于消息持久化）
     * @param emitter        SSE 发射器
     */
    public void streamChat(String question, Long kbId, Long conversationId, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                doStreamChat(question, kbId, conversationId, emitter);
            } catch (Exception e) {
                log.error("RAG 流式问答异常: kbId={}, question={}", kbId, question, e);
                emitter.completeWithError(e);
            }
        }, taskExecutor);
    }

    /**
     * 核心 RAG 业务逻辑
     */
    private void doStreamChat(String question, Long kbId, Long conversationId, SseEmitter emitter) throws Exception {
        long startTime = System.currentTimeMillis();
        // ===== [1] 向量化生成 (Embedding) =====
        // 将用户的问题"指纹化"，转成一串 1536 维（默认）的浮点数
        float[] queryVector = embeddingService.embed(question);

        // ===== [2] 语义缓存查询 =====
        // 看看数据库里有没有之前保存过的、语义非常接近的问题及其答案
        Optional<String> cached = semanticCacheService.tryGetCachedAnswer(question, kbId, queryVector);
        if (cached.isPresent()) {
            log.info("命中语义缓存: question={}", question);
            sendSseEvent(emitter, "token", cached.get()); // 直接发送缓存的答案
            sendSseEvent(emitter, "sources", "[]");      // 缓存暂不存储来源
            emitter.complete(); // 结束 SSE
            return;
        }

        // ===== [3] 向量检索 (L2 缓存 → L3 缓存 → pgvector DB) =====
        // 传入 question 用于 L2 检索缓存的 Key 计算
        List<RetrievalResult> contexts = retrievalService.retrieve(queryVector, kbId, question);
        log.info("RAG 检索完成: kbId={}, 命中 {} 条上下文", kbId, contexts.size());

        // ===== [4] Prompt 组装 =====
        // 把找到的上下文片段拼在一起，作为背景资料，连同问题一起喂给模型
        String systemPrompt = promptBuilder.buildSystemPrompt(contexts);
        String userMessage = promptBuilder.buildUserMessage(question);

        // ===== [5] LLM 流式推理 (使用 SSE 协议) =====
        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.generate(
                List.of(
                        dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                        dev.langchain4j.data.message.UserMessage.from(userMessage)
                ),
                new dev.langchain4j.model.StreamingResponseHandler<>() {
                    // 模型每产生一个字符/Token，就会调用一次这个方法
                    @Override
                    public void onNext(String token) {
                        fullResponse.append(token); // 累计完整回答，用于后续存入缓存
                        try {
                            sendSseEvent(emitter, "token", token); // 实时发给用户
                        } catch (IOException e) {
                            log.warn("SSE 发送失败", e);
                        }
                    }

                    // 如果模型生成过程中断网或报错
                    @Override
                    public void onError(Throwable error) {
                        log.error("LLM 流式推理错误", error);
                        emitter.completeWithError(error);
                    }

                    @Override
                    public void onComplete(dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response) {
                        try {
                            // ===== [6] 发送引用来源 =====
                            String sourcesJson = objectMapper.writeValueAsString(
                                    contexts.stream().map(c -> new SourceRef(c.getChunkId(), c.getScore(), c.getSnippet())).toList()
                            );
                            sendSseEvent(emitter, "sources", sourcesJson);

                            String answer = fullResponse.toString();
                            int latencyMs = (int) (System.currentTimeMillis() - startTime);

                            // ===== [7] 持久化对话消息 =====
                            if (conversationId != null) {
                                chatService.saveMessage(conversationId, question, answer, sourcesJson, latencyMs);
                            }

                            // ===== [8] 写入语义缓存 =====
                            if (!answer.isBlank()) {
                                semanticCacheService.cacheAnswer(question, answer, kbId, queryVector);
                            }

                            emitter.complete();
                        } catch (Exception e) {
                            log.error("SSE 完成阶段异常", e);
                            emitter.completeWithError(e);
                        }
                    }
                }
        );
    }

    /**
     * 发送 SSE 事件的辅助方法
     */
    private void sendSseEvent(SseEmitter emitter, String eventName, String data) throws IOException {
        // 创建 EventBuilder 并明确指定事件名和数据
        SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                .name(eventName)
                .data(data);
        
        // 显式调用 send(SseEventBuilder) 方法，消除对父类 send(Object) 的歧义
        emitter.send(eventBuilder);
    }

    /**
     * 引用来源的精简结构体，用于 JSON 序列化返回给前端
     */
    private record SourceRef(Long chunkId, double score, String snippet) {}

}
