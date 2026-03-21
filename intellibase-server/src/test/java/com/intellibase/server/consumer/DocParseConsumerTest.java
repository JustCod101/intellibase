package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.doc.DocParseService;
import com.intellibase.server.service.doc.TextSplitter;
import com.intellibase.server.service.kb.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档解析消费者 (DocParseConsumer) 单元测试类
 * <p>
 * 核心测试目标：
 * 验证在收到 RabbitMQ 消息后，消费者是否能够正确驱动以下流水线：
 * 1. 【状态反馈】更新数据库中的文档状态为 "解析中" (PARSING)
 * 2. 【文件获取】调用 MinIO 服务下载原始文件流
 * 3. 【文本提取】调用 Tika 解析服务将二进制文件转换为纯文本字符串
 * 4. 【智能分块】调用 TextSplitter 将长文本切分为适合向量化的短句块
 * 5. 【进度推进】更新数据库状态为 "向量化中" (EMBEDDING)
 * 6. 【异步接力】将分块结果打包，通过 RabbitMQ 发送给向量化服务 (EmbedConsumer)
 */
@ExtendWith(MockitoExtension.class)
class DocParseConsumerTest {

    // 模拟 MinIO 对象存储服务，用于文件的下载
    @Mock
    private MinioService minioService;

    // 模拟文档解析服务，内部封装了 Apache Tika 逻辑
    @Mock
    private DocParseService docParseService;

    // 模拟文本切分服务，用于实现递归字符切分
    @Mock
    private TextSplitter textSplitter;

    // 模拟数据库映射层，用于更新 document 表的状态
    @Mock
    private DocumentMapper documentMapper;

    // 模拟 RabbitMQ 模板，验证是否向后端队列发送了后续任务
    @Mock
    private RabbitTemplate rabbitTemplate;

    // 将上述 Mock 对象自动注入到被测试类中
    @InjectMocks
    private DocParseConsumer docParseConsumer;

    private DocParseMessage message;

    /**
     * 在每个测试用例运行前，初始化模拟的消息对象
     */
    @BeforeEach
    void setUp() {
        // 构建一条模拟的 RabbitMQ 消息，代表有一个 PDF 需要解析
        message = DocParseMessage.builder()
                .docId(101L)        // 文档主键 ID
                .kbId(10L)          // 所属知识库 ID
                .fileKey("kb/10/test.pdf") // 文件在存储中的路径
                .fileType("pdf")    // 文件类型
                .chunkSize(500)     // 切片大小建议值
                .chunkOverlap(50)   // 切片重叠建议值
                .build();
    }

    /**
     * 测试场景：标准解析成功流程
     * 预期：状态正确流转，且产生 1 个批次的向量化消息发送
     */
    @Test
    @DisplayName("解析成功 - 验证完整流水线逻辑")
    void handleDocParse_Success() throws Exception {
        // 1. 设置模拟返回值：MinIO 返回一个假的文件流
        InputStream mockStream = new ByteArrayInputStream("fake pdf content".getBytes());
        when(minioService.downloadFile(message.getFileKey())).thenReturn(mockStream);
        
        // 2. 设置模拟返回值：Tika 成功提取出一段文本
        String extractedText = "This is the content parsed from a PDF.";
        when(docParseService.parse(any(InputStream.class), eq("pdf"))).thenReturn(extractedText);
        
        // 3. 设置模拟返回值：TextSplitter 产生 3 个分块
        // 修正：TextChunk 构造函数参数顺序为 (index, content, tokenCount)
        List<TextChunk> mockChunks = new ArrayList<>();
        mockChunks.add(new TextChunk(0, "Part 1", 20));
        mockChunks.add(new TextChunk(1, "Part 2", 20));
        mockChunks.add(new TextChunk(2, "Part 3", 20));
        when(textSplitter.split(extractedText, 500, 50)).thenReturn(mockChunks);

        // --- 执行被测方法 ---
        docParseConsumer.handleDocParse(message);

        // 4. 验证行为：是否先将状态改为 PARSING
        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_PARSING);
        
        // 5. 验证行为：解析完成后，是否将状态推进到 EMBEDDING
        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_EMBEDDING);
        
        // 6. 验证消息发送：确认是否向 doc.embed.queue 发送了包含 3 个分块的消息
        ArgumentCaptor<EmbedBatchMessage> batchCaptor = ArgumentCaptor.forClass(EmbedBatchMessage.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(Constants.QUEUE_DOC_EMBED), batchCaptor.capture());
        
        EmbedBatchMessage sentMsg = batchCaptor.getValue();
        assertEquals(101L, sentMsg.getDocId());
        assertEquals(3, sentMsg.getChunks().size());
        assertEquals(1, sentMsg.getTotalBatches(), "分块少于100个，总批次数应为1");
        assertTrue(sentMsg.isLastBatch(), "由于分块少于100个，该批次应标记为最后一批");
    }

    /**
     * 测试场景：大文件分批次发送
     * 背景：为了防止消息体过大，消费者会将分块按 100 个一组拆分发送
     * 预期：产生 2 次 MQ 发送请求，且最后一次标记为 lastBatch = true
     */
    @Test
    @DisplayName("分批发送 - 验证大文档切分后的 MQ 批量分送逻辑")
    void handleDocParse_MultiBatch_Success() throws Exception {
        // 模拟产生 120 个分块，这将导致 MQ 发送 2 次 (100 + 20)
        List<TextChunk> mockChunks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            mockChunks.add(new TextChunk(i, "chunk text " + i, 30));
        }

        when(minioService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream("...".getBytes()));
        when(docParseService.parse(any(), anyString())).thenReturn("large text");
        when(textSplitter.split(anyString(), anyInt(), anyInt())).thenReturn(mockChunks);

        // --- 执行 ---
        docParseConsumer.handleDocParse(message);

        // 验证：MQ 确实发送了两次
        verify(rabbitTemplate, times(2)).convertAndSend(eq(Constants.QUEUE_DOC_EMBED), any(EmbedBatchMessage.class));
        
        // 验证分批标记位是否正确
        ArgumentCaptor<EmbedBatchMessage> batchCaptor = ArgumentCaptor.forClass(EmbedBatchMessage.class);
        verify(rabbitTemplate, atLeast(2)).convertAndSend(anyString(), batchCaptor.capture());
        
        List<EmbedBatchMessage> batches = batchCaptor.getAllValues();
        assertEquals(2, batches.get(0).getTotalBatches(), "每个批次消息都应携带总批次数=2");
        assertEquals(2, batches.get(1).getTotalBatches());
        assertFalse(batches.get(0).isLastBatch(), "第一批次 (100个) 不应标记为结束");
        assertTrue(batches.get(1).isLastBatch(), "第二批次 (20个) 应标记为结束");
    }

    /**
     * 测试场景：文本内容为空
     * 预期：标记 FAILED 并抛出 AmqpRejectAndDontRequeueException（跳过重试直接进入 DLQ）
     */
    @Test
    @DisplayName("容错处理 - 提取文本为空时应标记失败并抛出不可重试异常")
    void handleDocParse_EmptyText_SetsStatusFailed() throws Exception {
        // 模拟文件内容提取结果为空白字符串
        when(minioService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream("".getBytes()));
        when(docParseService.parse(any(), anyString())).thenReturn("  ");

        // 空内容属于不可重试的业务错误，应抛出 AmqpRejectAndDontRequeueException
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> docParseConsumer.handleDocParse(message));

        // 验证：最终数据库状态是否为 FAILED
        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_FAILED);
        // 验证：绝对不能向向量化队列发消息
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(EmbedBatchMessage.class));
    }

    /**
     * 测试场景：运行期瞬时异常（如 MinIO 网络不可达）
     * 预期：异常向上抛出，由 Spring AMQP RetryInterceptor 进行指数退避重试
     */
    @Test
    @DisplayName("健壮性 - 瞬时异常应抛出以触发 RetryInterceptor 重试")
    void handleDocParse_TransientException_ThrowsForRetry() throws Exception {
        // 模拟 MinIO 网络连接失败
        when(minioService.downloadFile(anyString())).thenThrow(new RuntimeException("Cloud Storage Unreachable"));

        // 瞬时异常应该直接抛出（不再内部 catch），由容器级重试机制处理
        assertThrows(RuntimeException.class,
                () -> docParseConsumer.handleDocParse(message));
    }
}
