package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.AiQualitySummary;
import com.research.assistant.dto.GoldenEvalMetrics;
import com.research.assistant.entity.AiQualityEvent;
import com.research.assistant.service.AiQualityEventService;
import com.research.assistant.service.GoldenEvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AI 质量事件查询接口，暂供内部观测和后续管理页面使用。 */
@RestController
@RequestMapping("/api/ai-quality")
public class AiQualityController {

    private final AiQualityEventService qualityEventService;
    private final GoldenEvalService goldenEvalService;

    public AiQualityController(AiQualityEventService qualityEventService,
                               GoldenEvalService goldenEvalService) {
        this.qualityEventService = qualityEventService;
        this.goldenEvalService = goldenEvalService;
    }

    @GetMapping("/summary")
    public Result<AiQualitySummary> summary(
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(qualityEventService.summarize(days));
    }

    @GetMapping("/recent")
    public Result<List<AiQualityEvent>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(qualityEventService.recent(limit));
    }

    @GetMapping("/golden")
    public Result<GoldenEvalMetrics> golden() {
        return Result.ok(goldenEvalService.evaluate());
    }
}
