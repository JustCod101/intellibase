package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.entity.Document;
import com.intellibase.server.domain.vo.DocumentVO;
import com.intellibase.server.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档管理服务单元测试
 * 
 * 覆盖：
 * 1. 文件上传 (校验、哈希计算、MinIO 上传、数据库写入、MQ 消息发送)
 * 2. 分页查询 (按知识库 ID、状态过滤)
 * 3. 文档删除 (MinIO 文件删除、数据库记录删除)
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private DocumentServiceImpl documentService;

    private final Long kbId = 1L;
    private final Long userId = 100L;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setUp() {
        // 模拟一个合法的 PDF 文件
        mockFile = new MockMultipartFile(
                "file",
                "test-document.pdf",
                "application/pdf",
                "dummy content to calculate hash".getBytes()
        );
    }

    /**
     * 测试：成功上传文档
     */
    @Test
    void upload_Success() throws Exception {
        // 模拟数据库中尚未存在相同内容的文档
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        
        // 执行上传
        DocumentVO result = documentService.upload(kbId, mockFile, "{\"meta\": \"test\"}", userId);

        // 验证：返回的 VO 基础信息正确
        assertNotNull(result);
        assertEquals("test-document.pdf", result.getTitle());
        assertEquals("pdf", result.getFileType());
        assertEquals(Constants.DOC_STATUS_PENDING, result.getParseStatus());

        // 验证：MinIO 上传方法被调用，且路径符合规范 (kb/{kbId}/{uuid}.ext)
        verify(minioService, times(1)).uploadFile(startsWith("kb/" + kbId + "/"), eq(mockFile));
        
        // 验证：数据库插入方法被调用
        verify(documentMapper, times(1)).insert(any(Document.class));

        // 验证：MQ 解析消息已正确发送
        ArgumentCaptor<DocParseMessage> messageCaptor = ArgumentCaptor.forClass(DocParseMessage.class);
        verify(rabbitTemplate, times(1)).convertAndSend(eq(Constants.QUEUE_DOC_PARSE), messageCaptor.capture());
        
        DocParseMessage sentMessage = messageCaptor.getValue();
        assertEquals(kbId, sentMessage.getKbId());
        assertEquals("pdf", sentMessage.getFileType());
        assertNotNull(sentMessage.getFileKey());
    }

    /**
     * 测试：上传不支持的文件类型（如 .exe）
     */
    @Test
    void upload_UnsupportedFileType_ThrowsException() {
        MockMultipartFile exeFile = new MockMultipartFile(
                "file", "script.py", "text/plain", "print('hello')".getBytes()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            documentService.upload(kbId, exeFile, null, userId);
        });

        assertTrue(exception.getMessage().contains("不支持的文件类型"));
    }

    /**
     * 测试：相同内容的文档重复上传
     */
    @Test
    void upload_DuplicateContent_ThrowsException() {
        // 模拟数据库已存在该内容的哈希记录
        when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            documentService.upload(kbId, mockFile, null, userId);
        });

        assertEquals("该知识库中已存在相同内容的文档", exception.getMessage());
    }

    /**
     * 测试：分页查询文档列表
     */
    @Test
    void list_ReturnsPagedData() {
        // 准备 Mock 返回的分页数据
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

        // 执行查询
        IPage<DocumentVO> result = documentService.list(kbId, 1, 10, "SUCCESS");

        // 验证分页结果
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("report.docx", result.getRecords().get(0).getTitle());
    }

    /**
     * 测试：成功删除文档
     */
    @Test
    void delete_Success() throws Exception {
        // 1. 准备要删除的文档对象
        Document doc = new Document();
        doc.setId(500L);
        doc.setKbId(kbId);
        doc.setFileKey("kb/1/random-uuid.pdf");

        // 2. 模拟根据 ID 查询成功
        when(documentMapper.selectById(500L)).thenReturn(doc);

        // 3. 执行删除操作
        documentService.delete(kbId, 500L);

        // 4. 验证：MinIO 上的物理文件被删除
        verify(minioService, times(1)).deleteFile("kb/1/random-uuid.pdf");
        
        // 5. 验证：数据库中的记录被删除
        verify(documentMapper, times(1)).deleteById(500L);
    }

    /**
     * 测试：删除不存在的文档
     */
    @Test
    void delete_NotFound_ThrowsException() {
        // 模拟数据库返回空
        when(documentMapper.selectById(anyLong())).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            documentService.delete(kbId, 999L);
        });

        assertEquals("文档不存在", exception.getMessage());
    }
}
