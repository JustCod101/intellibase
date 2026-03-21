package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.mq.IdempotencyService;
import com.intellibase.server.service.rag.CacheEvictionService;
import com.intellibase.server.service.rag.EmbedBatchTracker;
import com.intellibase.server.service.rag.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 向量化消费者 (EmbedConsumer) 单元测试类
 * <p>
 * 核心测试目标：
 * 验证在收到分块向量化消息后，消费者是否能够正确驱动以下流水线：
 * 1. 【内容提取】从 MQ 消息中提取出待向量化的纯文本列表
 * 2. 【模型调用】调用 EmbeddingService 获取每个文本块的高维向量
 * 3. 【格式转换】将 float[] 向量转换为 pgvector 所需的字符串格式
 * 4. 【批量入库】调用 DocumentChunkMapper 将文本、向量及其元数据批量存入数据库
 * 5. 【进度检查】判断是否为最后一个批次，并据此更新文档的总体解析状态
 */
@ExtendWith(MockitoExtension.class)
class EmbedConsumerTest {

    // 模拟 Embedding 服务，负责调用大模型 API 获取向量
    @Mock
    private EmbeddingService embeddingService;

    // 模拟文档分块映射层，负责将向量和文本存入 pgvector
    @Mock
    private DocumentChunkMapper documentChunkMapper;

    // 模拟文档映射层，用于更新文档的整体状态和最终分块计数
    @Mock
    private DocumentMapper documentMapper;

    // 模拟缓存清除服务
    @Mock
    private CacheEvictionService cacheEvictionService;

    // 模拟 Redis 批次追踪器
    @Mock
    private EmbedBatchTracker embedBatchTracker;

    // 模拟幂等性服务
    @Mock
    private IdempotencyService idempotencyService;

    // 被测试的消费者实例
    @InjectMocks
    private EmbedConsumer embedConsumer;

    private EmbedBatchMessage message;

    /**
     * 在每个测试用例运行前，初始化模拟的消息对象
     */
    @BeforeEach
    void setUp() {
        // 构建 3 个测试分块
        List<TextChunk> chunks = new ArrayList<>();
        // TextChunk 构造函数顺序为 (index, content, tokenCount)
        chunks.add(new TextChunk(0, "Chunk content 0", 25));
        chunks.add(new TextChunk(1, "Chunk content 1", 30));
        chunks.add(new TextChunk(2, "Chunk content 2", 20));

        // 构建 MQ 消息：docId=200, kbId=10, 包含 3 个分块，总计 3 个分块，共 2 个批次
        message = EmbedBatchMessage.builder()
                .messageId("embed-msg-001")
                .docId(200L)
                .kbId(10L)
                .chunks(chunks)
                .totalChunks(3)
                .totalBatches(2)
                .lastBatch(false)
                .build();
    }

    /**
     * 测试场景：普通批次向量化成功（尚有其他批次未完成）
     * 预期：调用 Embedding API，批量存入数据库，但 Redis 计数器未达标，不触发 COMPLETED
     */
    @Test
    @DisplayName("向量化处理 - 普通批次成功逻辑（计数器未达标）")
    void handleEmbed_Success_NotAllBatchesDone() {
        when(idempotencyService.tryAcquire("embed-msg-001")).thenReturn(true);

        // 1. 设置模拟返回值：模拟 Embedding API 返回 3 个向量 (维度假设为 3)
        List<float[]> mockVectors = List.of(
                new float[]{0.1f, 0.2f, 0.3f},
                new float[]{0.4f, 0.5f, 0.6f},
                new float[]{0.7f, 0.8f, 0.9f}
        );
        when(embeddingService.embedBatch(anyList())).thenReturn(mockVectors);

        // Redis 计数器返回 false — 还有其他批次未完成
        when(embedBatchTracker.incrementAndCheck(200L, 2)).thenReturn(false);

        // --- 执行被测方法 ---
        embedConsumer.handleEmbed(message);

        // 2. 验证行为：是否正确提取了 3 段文本并调用了 Embedding 接口
        verify(embeddingService).embedBatch(argThat(list -> list.size() == 3));

        // 3. 验证行为：是否调用了批量入库方法
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> vectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper).batchInsertWithVector(chunksCaptor.capture(), vectorsCaptor.capture());

        List<DocumentChunk> capturedChunks = chunksCaptor.getValue();
        assertEquals(3, capturedChunks.size());
        assertEquals(200L, capturedChunks.get(0).getDocId());
        assertEquals(0, capturedChunks.get(0).getChunkIndex());

        // 4. 验证行为：Redis 计数器未达标，不应该调用 updateChunkCount
        verify(documentMapper, never()).updateChunkCount(anyLong(), anyInt(), anyString());
    }

    /**
     * 测试场景：当前批次是最后一个完成的（所有批次均已处理完毕）
     * 预期：除正常入库外，Redis 计数器返回 true，触发 COMPLETED 状态更新
     */
    @Test
    @DisplayName("向量化处理 - 所有批次完成后触发状态更新（Redis 原子计数）")
    void handleEmbed_Success_AllBatchesDone() {
        when(idempotencyService.tryAcquire("embed-msg-001")).thenReturn(true);

        // 设置模拟返回值
        List<float[]> mockVectors = List.of(
                new float[]{0.1f},
                new float[]{0.2f},
                new float[]{0.3f}
        );
        when(embeddingService.embedBatch(anyList())).thenReturn(mockVectors);

        // Redis 计数器返回 true — 所有批次均已完成
        when(embedBatchTracker.incrementAndCheck(200L, 2)).thenReturn(true);

        // --- 执行 ---
        embedConsumer.handleEmbed(message);

        // 验证：所有批次完成后触发文档完成的状态更新
        verify(documentMapper).updateChunkCount(eq(200L), eq(3), eq(Constants.DOC_STATUS_COMPLETED));
        // 验证：缓存被清除
        verify(cacheEvictionService).evictByDocument(eq(200L), eq(10L));
    }

    /**
     * 测试场景：Embedding 接口调用异常（API 超时/限流等瞬时故障）
     * 预期：异常向上抛出，由 Spring AMQP RetryInterceptor 进行指数退避重试
     */
    @Test
    @DisplayName("容错处理 - Embedding 接口报错时应抛出异常以触发重试")
    void handleEmbed_EmbeddingServiceFails_ThrowsForRetry() {
        when(idempotencyService.tryAcquire("embed-msg-001")).thenReturn(true);

        // 模拟 Embedding 服务抛出超时异常
        when(embeddingService.embedBatch(anyList())).thenThrow(new RuntimeException("OpenAI API Timeout"));

        // 瞬时异常应抛出，由容器级重试机制处理；重试耗尽后由 DlqConsumer 标记 FAILED
        assertThrows(RuntimeException.class, () -> embedConsumer.handleEmbed(message));

        // 验证：不应该尝试执行任何数据库插入操作
        verify(documentChunkMapper, never()).batchInsertWithVector(anyList(), anyList());
    }

    /**
     * 测试场景：数据库批量插入异常
     * 预期：异常向上抛出，触发重试
     */
    @Test
    @DisplayName("容错处理 - 数据库写入失败时应抛出异常以触发重试")
    void handleEmbed_DatabaseFails_ThrowsForRetry() {
        when(idempotencyService.tryAcquire("embed-msg-001")).thenReturn(true);

        // 1. Embedding 成功（返回 3 个向量以匹配 3 个分块）
        List<float[]> mockVectors = List.of(
                new float[]{0.1f},
                new float[]{0.2f},
                new float[]{0.3f}
        );
        when(embeddingService.embedBatch(anyList())).thenReturn(mockVectors);

        // 2. 模拟数据库写入主键冲突或网络中断
        doThrow(new RuntimeException("DB Connection Error"))
                .when(documentChunkMapper).batchInsertWithVector(anyList(), anyList());

        // 瞬时异常应抛出，由容器级重试机制处理
        assertThrows(RuntimeException.class, () -> embedConsumer.handleEmbed(message));

        // 验证：失败时应释放幂等锁，允许重试
        verify(idempotencyService).release("embed-msg-001");
    }

    /**
     * 测试场景：重复消息（幂等性拦截）
     * 预期：直接跳过，不执行任何业务逻辑
     */
    @Test
    @DisplayName("幂等性 - 重复消息应被跳过")
    void handleEmbed_DuplicateMessage_Skipped() {
        when(idempotencyService.tryAcquire("embed-msg-001")).thenReturn(false);

        embedConsumer.handleEmbed(message);

        // 验证：不应执行任何业务逻辑
        verify(embeddingService, never()).embedBatch(anyList());
        verify(documentChunkMapper, never()).batchInsertWithVector(anyList(), anyList());
        verify(embedBatchTracker, never()).incrementAndCheck(anyLong(), anyInt());
    }
}
