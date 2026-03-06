package com.intellibase.server.service.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.intellibase.server.domain.dto.CreateKbRequest;
import com.intellibase.server.domain.dto.UpdateKbRequest;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.domain.vo.KnowledgeBaseVO;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String DEFAULT_CHUNK_STRATEGY = "{\"size\":512,\"overlap\":64}";

    @Override
    public KnowledgeBaseVO create(CreateKbRequest request, Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setEmbeddingModel(
                StringUtils.hasText(request.getEmbeddingModel())
                        ? request.getEmbeddingModel() : DEFAULT_EMBEDDING_MODEL);
        kb.setChunkStrategy(
                StringUtils.hasText(request.getChunkStrategy())
                        ? request.getChunkStrategy() : DEFAULT_CHUNK_STRATEGY);
        kb.setDocCount(0);
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(userId);
        // tenant_id 暂时使用 userId，后续接入多租户体系后替换
        kb.setTenantId(userId);

        knowledgeBaseMapper.insert(kb);
        log.info("知识库已创建: id={}, name={}", kb.getId(), kb.getName());

        return toVO(kb);
    }

    @Override
    public IPage<KnowledgeBaseVO> list(int page, int size, String keyword) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(KnowledgeBase::getName, keyword)
                    .or()
                    .like(KnowledgeBase::getDescription, keyword);
        }
        wrapper.orderByDesc(KnowledgeBase::getCreatedAt);

        IPage<KnowledgeBase> pageResult = knowledgeBaseMapper.selectPage(
                new Page<>(page, size), wrapper);

        return pageResult.convert(this::toVO);
    }

    @Override
    public KnowledgeBaseVO getById(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: id=" + id);
        }
        return toVO(kb);
    }

    @Override
    public KnowledgeBaseVO update(Long id, UpdateKbRequest request) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: id=" + id);
        }

        if (StringUtils.hasText(request.getName())) {
            kb.setName(request.getName());
        }
        if (request.getDescription() != null) {
            kb.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())) {
            kb.setStatus(request.getStatus());
        }

        knowledgeBaseMapper.updateById(kb);
        log.info("知识库已更新: id={}", id);

        return toVO(kb);
    }

    @Override
    public void delete(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: id=" + id);
        }
        knowledgeBaseMapper.deleteById(id);
        log.info("知识库已删除: id={}, name={}", id, kb.getName());
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .embeddingModel(kb.getEmbeddingModel())
                .chunkStrategy(kb.getChunkStrategy())
                .docCount(kb.getDocCount())
                .status(kb.getStatus())
                .createdAt(kb.getCreatedAt())
                .build();
    }

}
