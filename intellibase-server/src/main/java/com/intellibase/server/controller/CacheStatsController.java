package com.intellibase.server.controller;

import com.intellibase.server.common.Result;
import com.intellibase.server.service.rag.CacheStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 缓存统计接口（管理员专用）
 */
@RestController
@RequestMapping("/api/v1/admin/cache")
@RequiredArgsConstructor
public class CacheStatsController {

    private final CacheStatsService cacheStatsService;

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        return Result.ok(cacheStatsService.getStats());
    }

    @PostMapping("/stats/reset")
    public Result<Void> resetStats() {
        cacheStatsService.reset();
        return Result.ok();
    }

}
