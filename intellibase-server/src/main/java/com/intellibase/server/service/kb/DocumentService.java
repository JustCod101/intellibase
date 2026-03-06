package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.domain.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    /**
     * 上传文档：存储到 MinIO，写入元数据，发送解析消息到 MQ
     */
    DocumentVO upload(Long kbId, MultipartFile file, String metadata, Long userId);

    /**
     * 文档列表（分页）
     */
    IPage<DocumentVO> list(Long kbId, Integer page, Integer size, String status);

    /**
     * 删除文档：删除 MinIO 文件 + 数据库记录（级联删除分块）
     */
    void delete(Long kbId, Long docId);

}
