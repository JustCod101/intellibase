package com.intellibase.server.service.rag;

import com.intellibase.server.domain.entity.DocumentChunk;
import com.intellibase.server.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 稀疏关键词召回服务。
 */
@Service
@RequiredArgsConstructor
public class SparseRecallService {

    private final DocumentChunkMapper documentChunkMapper;

    public List<DocumentChunk> recall(String lexicalQuery, Long kbId, int topK) {
        if (!StringUtils.hasText(lexicalQuery) || topK <= 0) {
            return List.of();
        }
        return documentChunkMapper.findLexicalMatches(lexicalQuery, kbId, topK);
    }
}
