package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.DocumentMapper;
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

        // 构建 MQ 消息：docId=200, kbId=10, 包含 3 个分块，总计 3 个分块
        message = EmbedBatchMessage.builder()
                .docId(200L)
                .kbId(10L)
                .chunks(chunks)
                .totalChunks(3)
                .lastBatch(false) // 默认先测试非最后一批的情况
                .build();
    }

    /**
     * 测试场景：普通批次向量化成功（非最后一批）
     * 预期：调用 Embedding API，批量存入数据库，但不更新文档为 COMPLETED 状态
     */
    @Test
    @DisplayName("向量化处理 - 普通批次成功逻辑")
    void handleEmbed_Success_NotLastBatch() {
        // 1. 设置模拟返回值：模拟 Embedding API 返回 3 个向量 (维度假设为 3)
        List<float[]> mockVectors = List.of(
                new float[]{0.1f, 0.2f, 0.3f},
                new float[]{0.4f, 0.5f, 0.6f},
                new float[]{0.7f, 0.8f, 0.9f}
        );
        when(embeddingService.embedBatch(anyList())).thenReturn(mockVectors);

        // --- 执行被测方法 ---
        embedConsumer.handleEmbed(message);

        // 2. 验证行为：是否正确提取了 3 段文本并调用了 Embedding 接口
        verify(embeddingService).embedBatch(argThat(list -> list.size() == 3));

        // 3. 验证行为：是否调用了批量入库方法
        // 我们通过 ArgumentCaptor 捕获参数，进一步验证数据是否拼装正确
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> vectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper).batchInsertWithVector(chunksCaptor.capture(), vectorsCaptor.capture());

        List<DocumentChunk> capturedChunks = chunksCaptor.getValue();
        assertEquals(3, capturedChunks.size());
        assertEquals(200L, capturedChunks.get(0).getDocId());
        assertEquals(0, capturedChunks.get(0).getChunkIndex()); // 验证索引顺序

        // 4. 验证行为：因为非最后一批，不应该调用 updateChunkCount
        verify(documentMapper, never()).updateChunkCount(anyLong(), anyInt(), anyString());
    }

    /**
     * 测试场景：最后一批向量化成功
     * 预期：除正常入库外，还需将文档状态标记为 COMPLETED
     */
    @Test
    @DisplayName("向量化处理 - 最后一批次成功并完成文档状态更新")
    void handleEmbed_Success_LastBatch() {
        // 标记为最后一批
        message.setLastBatch(true);

        // 设置模拟返回值：确保向量数量与消息中的分块数量 (3个) 一致
        List<float[]> mockVectors = List.of(
                new float[]{0.1f},
                new float[]{0.2f},
                new float[]{0.3f}
        );
        when(embeddingService.embedBatch(anyList())).thenReturn(mockVectors);

        // --- 执行 ---
        embedConsumer.handleEmbed(message);

        // 验证：是否触发了文档完成的状态更新
        // 预期调用 documentMapper.updateChunkCount(docId, totalChunks, "COMPLETED")
        verify(documentMapper).updateChunkCount(eq(200L), eq(3), eq(Constants.DOC_STATUS_COMPLETED));
    }

    /**
     * 测试场景：Embedding 接口调用异常
     * 预期：捕获异常，并将文档状态置为 FAILED
     */
    @Test
    @DisplayName("容错处理 - Embedding 接口报错时文档状态应标记为失败")
    void handleEmbed_EmbeddingServiceFails_SetsStatusFailed() {
        // 模拟 Embedding 服务抛出超时异常
        when(embeddingService.embedBatch(anyList())).thenThrow(new RuntimeException("OpenAI API Timeout"));

        // --- 执行 ---
        embedConsumer.handleEmbed(message);

        // 验证：最终数据库状态是否被标记为 FAILED，以便前端向用户展示错误
        verify(documentMapper).updateStatus(200L, Constants.DOC_STATUS_FAILED);
        // 验证：不应该尝试执行任何数据库插入操作
        verify(documentChunkMapper, never()).batchInsertWithVector(anyList(), anyList());
    }

    /**
     * 测试场景：数据库批量插入异常
     * 预期：捕获异常，并将文档状态置为 FAILED
     */
    @Test
    @DisplayName("容错处理 - 数据库写入失败时能正确处理异常状态")
    void handleEmbed_DatabaseFails_SetsStatusFailed() {
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

        // --- 执行 ---
        embedConsumer.handleEmbed(message);

        // 验证：虽然 Embedding 成功了，但由于存库失败，文档最终仍需标记为 FAILED
        verify(documentMapper).updateStatus(200L, Constants.DOC_STATUS_FAILED);
    }
}
