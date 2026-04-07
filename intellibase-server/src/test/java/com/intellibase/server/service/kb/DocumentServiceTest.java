package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.entity.Document;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.domain.event.DocParseEvent;
import com.intellibase.server.domain.vo.DocumentVO;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import com.intellibase.server.service.doc.ChunkStrategyResolver;
import com.intellibase.server.service.rag.CacheEvictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CacheEvictionService cacheEvictionService;

    private DocumentServiceImpl documentService;

    private final Long kbId = 1L;
    private final Long userId = 100L;
    private MockMultipartFile mockFile;
    @BeforeEach
    void setUp() {
        ChunkStrategyResolver resolver = new ChunkStrategyResolver(new ObjectMapper());
        documentService = new DocumentServiceImpl(
                documentMapper,
                knowledgeBaseMapper,
                minioService,
                eventPublisher,
                cacheEvictionService,
                resolver
        );

        mockFile = new MockMultipartFile(
                "file",
                "test-document.pdf",
                "application/pdf",
                "dummy content to calculate hash".getBytes()
        );

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setChunkStrategy("""
                {"version":2,"type":"STRUCTURE_AWARE","size":800,"overlap":120,"minSize":200,"normalizeWhitespace":true}
                """.trim());
        when(knowledgeBaseMapper.selectById(kbId)).thenReturn(kb);
    }

    @Test
    void upload_Success_UsesKnowledgeBaseChunkStrategySnapshot() throws Exception {
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        doAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(501L);
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        DocumentVO result = documentService.upload(kbId, mockFile, "{\"meta\":\"test\"}", userId);

        assertNotNull(result);
        assertEquals("test-document.pdf", result.getTitle());
        assertEquals(Constants.DOC_STATUS_PENDING, result.getParseStatus());

        verify(minioService).uploadFile(startsWith("kb/" + kbId + "/"), eq(mockFile));
        verify(documentMapper).insert(any(Document.class));

        ArgumentCaptor<DocParseEvent> eventCaptor = ArgumentCaptor.forClass(DocParseEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        DocParseMessage sentMessage = eventCaptor.getValue().getMessage();
        assertEquals(kbId, sentMessage.getKbId());
        assertEquals("pdf", sentMessage.getFileType());
        assertEquals(800, sentMessage.getChunkSize());
        assertEquals(120, sentMessage.getChunkOverlap());
        assertNotNull(sentMessage.getChunkStrategy());
        assertEquals("STRUCTURE_AWARE", sentMessage.getChunkStrategy().getType());
        assertEquals(200, sentMessage.getChunkStrategy().getMinSize());
    }

    @Test
    void upload_UnsupportedFileType_ThrowsException() {
        MockMultipartFile exeFile = new MockMultipartFile(
                "file", "script.py", "text/plain", "print('hello')".getBytes()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                documentService.upload(kbId, exeFile, null, userId));

        assertTrue(exception.getMessage().contains("不支持的文件类型"));
    }

    @Test
    void upload_DuplicateContent_ThrowsException() {
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                documentService.upload(kbId, mockFile, null, userId));

        assertEquals("该知识库中已存在相同内容的文档", exception.getMessage());
    }

    @Test
    void list_ReturnsPagedData() {
        Document doc = new Document();
        doc.setId(101L);
        doc.setKbId(kbId);
        doc.setTitle("report.docx");
        doc.setParseStatus(Constants.DOC_STATUS_COMPLETED);
        doc.setCreatedAt(OffsetDateTime.now());

        Page<Document> mockPage = new Page<>(1, 10);
        mockPage.setRecords(Collections.singletonList(doc));
        mockPage.setTotal(1);

        when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

        IPage<DocumentVO> result = documentService.list(kbId, 1, 10, "SUCCESS");

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("report.docx", result.getRecords().get(0).getTitle());
    }

    @Test
    void delete_Success() throws Exception {
        Document doc = new Document();
        doc.setId(500L);
        doc.setKbId(kbId);
        doc.setFileKey("kb/1/random-uuid.pdf");

        when(documentMapper.selectById(500L)).thenReturn(doc);

        documentService.delete(kbId, 500L);

        verify(cacheEvictionService).evictByDocument(500L, kbId);
        verify(minioService).deleteFile("kb/1/random-uuid.pdf");
        verify(documentMapper).deleteById(500L);
    }

    @Test
    void delete_NotFound_ThrowsException() {
        when(documentMapper.selectById(anyLong())).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                documentService.delete(kbId, 999L));

        assertEquals("文档不存在", exception.getMessage());
    }
}
