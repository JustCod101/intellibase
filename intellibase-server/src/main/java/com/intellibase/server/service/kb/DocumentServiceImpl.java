package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intellibase.server.common.Constants;
import com.intellibase.server.domain.dto.DocParseMessage;
import com.intellibase.server.domain.entity.Document;
import com.intellibase.server.domain.vo.DocumentVO;
import com.intellibase.server.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 文档管理服务实现类
 * <p>
 * 职责：负责知识库中文件的上传、存储管理（MinIO）、元数据维护（数据库）以及发起异步解析流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    // 数据库操作对象，用于读写 document 表
    private final DocumentMapper documentMapper;
    // 自定义的 MinIO 服务，用于将文件实际存储到云端/对象存储服务器
    private final MinioService minioService;
    // Spring 提供的 RabbitMQ 操作模版，用于发送解析任务消息
    private final RabbitTemplate rabbitTemplate;

    /**
     * 上传文档流程
     * 
     * @param kbId     知识库ID（文件属于哪个库）
     * @param file     前端上传的原始文件对象
     * @param metadata 额外的元数据（如来源URL等 JSON 字符串）
     * @param userId   操作人ID
     * @return 返回封装好的文档视图对象
     */
    @Override
    public DocumentVO upload(Long kbId, MultipartFile file, String metadata, Long userId) {
        // 1. 获取文件名并提取后缀名进行格式检查
        String originalFilename = file.getOriginalFilename();
        String fileType = extractFileType(originalFilename);
        validateFileType(fileType); // 只允许 pdf/docx/md/txt

        // 2. 计算文件内容 SHA-256 哈希值
        // 作用：像人的指纹一样，内容相同的文件哈希值一定相同。用于实现“秒传”或防止重复上传。
        String contentHash = computeSha256(file);

        // 3. 检查同一个知识库下是否已经存在完全相同内容的文档
        Long existCount = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getKbId, kbId)
                        .eq(Document::getContentHash, contentHash)
        );
        if (existCount > 0) {
            throw new IllegalArgumentException("该知识库中已存在相同内容的文档");
        }

        // 4. 生成在 MinIO 中的存储路径（Object Key）
        // 规则：目录/知识库ID/随机UUID.后缀，防止文件名冲突
        String objectKey = "kb/" + kbId + "/" + UUID.randomUUID() + "." + fileType;
        try {
            // 调用 MinIO 服务将文件流上传到存储服务器
            minioService.uploadFile(objectKey, file);
        } catch (Exception e) {
            log.error("文件上传到 MinIO 失败", e);
            throw new RuntimeException("文件上传到 MinIO 失败", e);
        }

        // 5. 将文档信息记录到数据库中
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTitle(originalFilename); // 保存原始文件名作为标题
        doc.setFileKey(objectKey);      // 记录在 MinIO 中的唯一路径
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setContentHash(contentHash);
        doc.setParseStatus(Constants.DOC_STATUS_PENDING); // 初始状态为：待处理
        doc.setChunkCount(0);           // 初始分块数为 0
        doc.setMetadata(metadata);
        doc.setCreatedBy(userId);
        documentMapper.insert(doc);      // 执行插入，MyBatis-Plus 会回填 ID

        // 6. 异步处理：发送解析消息到消息队列 (RabbitMQ)
        // 为什么用异步？解析大文件非常耗时，不能让用户在上传界面一直转圈等待。
        // 后台会有专门的消费者 (DocParseConsumer) 接收这个消息并开始切分文档。
        DocParseMessage message = DocParseMessage.builder()
                .docId(doc.getId())
                .kbId(kbId)
                .fileKey(objectKey)
                .fileType(fileType)
                .chunkSize(512)   // 默认每个切片 512 字符
                .chunkOverlap(64) // 切片之间重叠 64 字符，保证语义连贯
                .build();
        rabbitTemplate.convertAndSend(Constants.QUEUE_DOC_PARSE, message);
        log.info("文档上传成功，已发送解析消息: docId={}, title={}", doc.getId(), originalFilename);

        return toVO(doc);
    }

    /**
     * 分页查询文档列表
     * 
     * @param kbId   知识库ID
     * @param page   当前页码
     * @param size   每页数量
     * @param status 状态过滤（可选，如：PENDING, SUCCESS, FAILED）
     */
    @Override
    public IPage<DocumentVO> list(Long kbId, Integer page, Integer size, String status) {
        // 构建查询条件
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                // 如果传入了状态，则按状态过滤；没传则查询全部
                .eq(StringUtils.hasText(status), Document::getParseStatus, status)
                .orderByDesc(Document::getCreatedAt); // 按创建时间倒序排列

        // 执行分页查询
        Page<Document> pageResult = documentMapper.selectPage(new Page<>(page, size), wrapper);

        // 将数据库实体 (Entity) 转换为前端需要的视图对象 (VO)
        return pageResult.convert(this::toVO);
    }

    /**
     * 删除文档
     * 
     * @param kbId  知识库ID（用于安全校验，确保用户只能删除自己库的文件）
     * @param docId 文档ID
     */
    @Override
    public void delete(Long kbId, Long docId) {
        // 1. 先查询文档信息
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new IllegalArgumentException("文档不存在");
        }

        // 2. 物理删除：从 MinIO 存储中删除文件，节省磁盘空间
        try {
            minioService.deleteFile(doc.getFileKey());
        } catch (Exception e) {
            // 如果文件服务出故障，记录警告但不中断流程，优先保证数据库一致性
            log.warn("MinIO 文件删除失败: {}", doc.getFileKey(), e);
        }

        // 3. 逻辑/物理删除：从数据库中删除记录
        // 注意：底层 document_chunk 表通常设置了外键级联删除，所以分块记录会自动清理
        documentMapper.deleteById(docId);
        log.info("文档已删除: docId={}, title={}", docId, doc.getTitle());
    }

    // ======================== 私有辅助方法 ========================

    /**
     * Entity 转 VO
     */
    private DocumentVO toVO(Document doc) {
        return DocumentVO.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .title(doc.getTitle())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .parseStatus(doc.getParseStatus())
                .chunkCount(doc.getChunkCount())
                .metadata(doc.getMetadata())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    /**
     * 从文件名提取后缀（例如：test.pdf -> pdf）
     */
    private String extractFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new IllegalArgumentException("无法识别文件类型");
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 校验文件格式是否在系统允许范围内
     */
    private void validateFileType(String fileType) {
        if (!fileType.matches("pdf|docx|md|txt")) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType + "，仅支持 pdf/docx/md/txt");
        }
    }

    /**
     * 计算文件的 SHA-256 哈希值
     * 哈希算法可以将任意大小的数据变成固定长度的字符串，内容变一点点，字符串就会完全不同。
     */
    private String computeSha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(file.getBytes());
            // 将字节数组转换为 16 进制字符串
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

}
