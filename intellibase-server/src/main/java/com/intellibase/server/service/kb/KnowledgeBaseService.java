package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.intellibase.server.domain.dto.CreateKbRequest;
import com.intellibase.server.domain.dto.UpdateKbRequest;
import com.intellibase.server.domain.vo.KnowledgeBaseVO;

public interface KnowledgeBaseService {

    KnowledgeBaseVO create(CreateKbRequest request, Long userId);

    IPage<KnowledgeBaseVO> list(int page, int size, String keyword);

    KnowledgeBaseVO getById(Long id);

    KnowledgeBaseVO update(Long id, UpdateKbRequest request);

    void delete(Long id);

}
