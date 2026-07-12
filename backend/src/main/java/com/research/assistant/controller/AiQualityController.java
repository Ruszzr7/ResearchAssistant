package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.AiQualitySummary;
import com.research.assistant.dto.AiQualityEventPage;
import com.research.assistant.dto.AiQualityEventQuery;
import com.research.assistant.dto.GoldenEvalMetrics;
import com.research.assistant.dto.RagGoldenEvalMetrics;
import com.research.assistant.dto.ResearchSynthesisGoldenEvalMetrics;
import com.research.assistant.entity.AiQualityEvent;
import com.research.assistant.service.AiQualityEventService;
import com.research.assistant.service.GoldenEvalService;
import com.research.assistant.service.RagGoldenEvalService;
import com.research.assistant.service.ResearchSynthesisGoldenEvalService;
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
    private final RagGoldenEvalService ragGoldenEvalService;
    private final ResearchSynthesisGoldenEvalService synthesisGoldenEvalService;

    public AiQualityController(AiQualityEventService qualityEventService,
                               GoldenEvalService goldenEvalService,
                               RagGoldenEvalService ragGoldenEvalService,
                               ResearchSynthesisGoldenEvalService synthesisGoldenEvalService) {
        this.qualityEventService = qualityEventService;
        this.goldenEvalService = goldenEvalService;
        this.ragGoldenEvalService = ragGoldenEvalService;
        this.synthesisGoldenEvalService = synthesisGoldenEvalService;
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

    @GetMapping("/events")
    public Result<AiQualityEventPage> events(AiQualityEventQuery query) {
        return Result.ok(qualityEventService.search(query));
    }

    @GetMapping("/golden")
    public Result<GoldenEvalMetrics> golden() {
        return Result.ok(goldenEvalService.evaluate());
    }

    @GetMapping("/rag-golden")
    public Result<RagGoldenEvalMetrics> ragGolden() {
        return Result.ok(ragGoldenEvalService.evaluate());
    }

    @GetMapping("/synthesis-golden")
    public Result<ResearchSynthesisGoldenEvalMetrics> synthesisGolden() {
        return Result.ok(synthesisGoldenEvalService.evaluate());
    }
}
