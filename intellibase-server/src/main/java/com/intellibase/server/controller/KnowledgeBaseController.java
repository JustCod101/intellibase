package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.common.Result;
import com.intellibase.server.domain.dto.CreateKbRequest;
import com.intellibase.server.domain.dto.UpdateKbRequest;
import com.intellibase.server.domain.vo.KnowledgeBaseVO;
import com.intellibase.server.service.kb.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public Result<KnowledgeBaseVO> create(@Valid @RequestBody CreateKbRequest request,
                                          Authentication authentication) {
        Long userId = Long.parseLong(authentication.getPrincipal().toString());
        return Result.ok(knowledgeBaseService.create(request, userId));
    }

    @GetMapping
    public Result<IPage<KnowledgeBaseVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return Result.ok(knowledgeBaseService.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> getById(@PathVariable Long id) {
        return Result.ok(knowledgeBaseService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public Result<KnowledgeBaseVO> update(@PathVariable Long id,
                                          @RequestBody UpdateKbRequest request) {
        return Result.ok(knowledgeBaseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.ok();
    }

}
