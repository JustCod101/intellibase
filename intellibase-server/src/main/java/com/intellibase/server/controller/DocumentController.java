package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.common.Result;
import com.intellibase.server.domain.vo.DocumentVO;
import com.intellibase.server.service.kb.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/kb/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     */
    @PostMapping
    public Result<DocumentVO> upload(@PathVariable Long kbId,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "metadata", required = false) String metadata,
                                     Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());
        DocumentVO vo = documentService.upload(kbId, file, metadata, userId);
        return Result.ok(vo);
    }

    /**
     * 文档列表（分页）
     */
    @GetMapping
    public Result<IPage<DocumentVO>> list(@PathVariable Long kbId,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer size,
                                          @RequestParam(required = false) String status) {
        IPage<DocumentVO> result = documentService.list(kbId, page, size, status);
        return Result.ok(result);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable Long kbId, @PathVariable Long docId) {
        documentService.delete(kbId, docId);
        return Result.ok();
    }

}
