package com.intellibase.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellibase.server.common.Result;
import com.intellibase.server.domain.entity.Conversation;
import com.intellibase.server.domain.entity.Document;
import com.intellibase.server.domain.entity.KnowledgeBase;
import com.intellibase.server.mapper.ConversationMapper;
import com.intellibase.server.mapper.DocumentMapper;
import com.intellibase.server.mapper.KnowledgeBaseMapper;
import com.intellibase.server.service.rag.CacheStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final ConversationMapper conversationMapper;
    private final CacheStatsService cacheStatsService;

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getPrincipal().toString());

        Map<String, Object> stats = new LinkedHashMap<>();

        // 知识库统计
        Long kbCount = knowledgeBaseMapper.selectCount(null);
        stats.put("kbCount", kbCount);

        // 文档统计
        Long docTotal = documentMapper.selectCount(null);
        Long docCompleted = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().eq(Document::getParseStatus, "COMPLETED"));
        Long docFailed = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().eq(Document::getParseStatus, "FAILED"));
        Long docProcessing = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().in(Document::getParseStatus, "PENDING", "PARSING", "EMBEDDING"));
        stats.put("docTotal", docTotal);
        stats.put("docCompleted", docCompleted);
        stats.put("docFailed", docFailed);
        stats.put("docProcessing", docProcessing);

        // 会话统计（当前用户）
        Long convCount = conversationMapper.selectCount(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getUserId, userId));
        stats.put("convCount", convCount);

        // 缓存统计
        stats.put("cache", cacheStatsService.getStats());

        return Result.ok(stats);
    }

}
