package com.intellibase.server.consumer;

import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.ChunkStrategy;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.dto.EmbedBatchMessage;
import com.intellibase.server.domain.dto.TextChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.service.doc.ChunkStrategyResolver;
import com.intellibase.server.service.doc.DocParseService;
import com.intellibase.server.service.doc.TextSplitter;
import com.intellibase.server.service.kb.MinioService;
import com.intellibase.server.service.mq.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocParseConsumerTest {

    @Mock
    private MinioService minioService;

    @Mock
    private DocParseService docParseService;

    @Mock
    private TextSplitter textSplitter;

    @Mock
    private ChunkStrategyResolver chunkStrategyResolver;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private DocParseConsumer docParseConsumer;

    private DocParseMessage message;
    private ChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = ChunkStrategy.builder()
                .version(2)
                .type("STRUCTURE_AWARE")
                .size(500)
                .overlap(50)
                .minSize(120)
                .normalizeWhitespace(true)
                .build();

        message = DocParseMessage.builder()
                .messageId("test-msg-001")
                .docId(101L)
                .kbId(10L)
                .fileKey("kb/10/test.pdf")
                .fileType("pdf")
                .chunkStrategy(strategy)
                .chunkSize(500)
                .chunkOverlap(50)
                .build();
    }

    @Test
    @DisplayName("解析成功 - 验证完整流水线逻辑")
    void handleDocParse_Success() throws Exception {
        when(idempotencyService.tryAcquire("test-msg-001")).thenReturn(true);

        InputStream mockStream = new ByteArrayInputStream("fake pdf content".getBytes());
        when(minioService.downloadFile(message.getFileKey())).thenReturn(mockStream);

        String extractedText = "This is the content parsed from a PDF.";
        when(docParseService.parse(any(InputStream.class), eq("pdf"))).thenReturn(extractedText);
        when(chunkStrategyResolver.resolve(strategy, 500, 50)).thenReturn(strategy);

        List<TextChunk> mockChunks = new ArrayList<>();
        mockChunks.add(new TextChunk(0, "Part 1", 20, "{\"blockType\":\"PARAGRAPH\"}"));
        mockChunks.add(new TextChunk(1, "Part 2", 20, "{\"blockType\":\"PARAGRAPH\"}"));
        mockChunks.add(new TextChunk(2, "Part 3", 20, "{\"blockType\":\"PARAGRAPH\"}"));
        when(textSplitter.split(extractedText, strategy)).thenReturn(mockChunks);

        docParseConsumer.handleDocParse(message);

        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_PARSING);
        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_EMBEDDING);

        ArgumentCaptor<EmbedBatchMessage> batchCaptor = ArgumentCaptor.forClass(EmbedBatchMessage.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(Constants.QUEUE_DOC_EMBED), batchCaptor.capture());

        EmbedBatchMessage sentMsg = batchCaptor.getValue();
        assertEquals(101L, sentMsg.getDocId());
        assertEquals(3, sentMsg.getChunks().size());
        assertEquals(1, sentMsg.getTotalBatches());
        assertNotNull(sentMsg.getMessageId());
        assertTrue(sentMsg.isLastBatch());
    }

    @Test
    @DisplayName("兼容旧消息 - chunkStrategy 缺失时回退 chunkSize/chunkOverlap")
    void handleDocParse_LegacyMessage_FallsBackToLegacyFields() throws Exception {
        DocParseMessage legacyMessage = DocParseMessage.builder()
                .messageId("legacy-msg-001")
                .docId(101L)
                .kbId(10L)
                .fileKey("kb/10/test.pdf")
                .fileType("pdf")
                .chunkSize(500)
                .chunkOverlap(50)
                .build();

        when(idempotencyService.tryAcquire("legacy-msg-001")).thenReturn(true);
        when(minioService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream("...".getBytes()));
        when(docParseService.parse(any(), anyString())).thenReturn("legacy text");
        when(chunkStrategyResolver.resolve(null, 500, 50)).thenReturn(strategy);
        when(textSplitter.split("legacy text", strategy)).thenReturn(List.of(new TextChunk(0, "legacy", 10)));

        docParseConsumer.handleDocParse(legacyMessage);

        verify(chunkStrategyResolver).resolve(null, 500, 50);
        verify(textSplitter).split("legacy text", strategy);
    }

    @Test
    @DisplayName("分批发送 - 验证大文档切分后的 MQ 批量分送逻辑")
    void handleDocParse_MultiBatch_Success() throws Exception {
        when(idempotencyService.tryAcquire("test-msg-001")).thenReturn(true);

        List<TextChunk> mockChunks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            mockChunks.add(new TextChunk(i, "chunk text " + i, 30));
        }

        when(minioService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream("...".getBytes()));
        when(docParseService.parse(any(), anyString())).thenReturn("large text");
        when(chunkStrategyResolver.resolve(strategy, 500, 50)).thenReturn(strategy);
        when(textSplitter.split("large text", strategy)).thenReturn(mockChunks);

        docParseConsumer.handleDocParse(message);

        verify(rabbitTemplate, times(2)).convertAndSend(eq(Constants.QUEUE_DOC_EMBED), any(EmbedBatchMessage.class));

        ArgumentCaptor<EmbedBatchMessage> batchCaptor = ArgumentCaptor.forClass(EmbedBatchMessage.class);
        verify(rabbitTemplate, atLeast(2)).convertAndSend(anyString(), batchCaptor.capture());

        List<EmbedBatchMessage> batches = batchCaptor.getAllValues();
        assertEquals(2, batches.get(0).getTotalBatches());
        assertEquals(2, batches.get(1).getTotalBatches());
        assertFalse(batches.get(0).isLastBatch());
        assertTrue(batches.get(1).isLastBatch());
    }

    @Test
    @DisplayName("容错处理 - 提取文本为空时应标记失败并抛出不可重试异常")
    void handleDocParse_EmptyText_SetsStatusFailed() throws Exception {
        when(idempotencyService.tryAcquire("test-msg-001")).thenReturn(true);
        when(minioService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream("".getBytes()));
        when(docParseService.parse(any(), anyString())).thenReturn("  ");

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> docParseConsumer.handleDocParse(message));

        verify(documentMapper).updateStatus(101L, Constants.DOC_STATUS_FAILED);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(EmbedBatchMessage.class));
    }

    @Test
    @DisplayName("健壮性 - 瞬时异常应抛出以触发 RetryInterceptor 重试")
    void handleDocParse_TransientException_ThrowsForRetry() throws Exception {
        when(idempotencyService.tryAcquire("test-msg-001")).thenReturn(true);
        when(minioService.downloadFile(anyString())).thenThrow(new RuntimeException("Cloud Storage Unreachable"));

        assertThrows(RuntimeException.class,
                () -> docParseConsumer.handleDocParse(message));

        verify(idempotencyService).release("test-msg-001");
    }

    @Test
    @DisplayName("幂等性 - 重复消息应被跳过")
    void handleDocParse_DuplicateMessage_Skipped() throws Exception {
        when(idempotencyService.tryAcquire("test-msg-001")).thenReturn(false);

        docParseConsumer.handleDocParse(message);

        verify(documentMapper, never()).updateStatus(anyLong(), anyString());
        verify(minioService, never()).downloadFile(anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(EmbedBatchMessage.class));
    }
}
