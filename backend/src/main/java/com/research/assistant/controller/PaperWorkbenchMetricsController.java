package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import com.research.assistant.dto.workbench.PdfWorkbenchMetricsSnapshot;
import com.research.assistant.service.workbench.PdfWorkbenchEvalService;
import com.research.assistant.service.workbench.PdfWorkbenchMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Aggregate observability endpoints for the PDF workbench. */
@RestController
@RequestMapping("/api/workbench")
public class PaperWorkbenchMetricsController {

    private final PdfWorkbenchMetricsService metricsService;
    private final PdfWorkbenchEvalService evalService;

    public PaperWorkbenchMetricsController(PdfWorkbenchMetricsService metricsService,
                                           PdfWorkbenchEvalService evalService) {
        this.metricsService = metricsService;
        this.evalService = evalService;
    }

    @GetMapping("/metrics")
    public Result<PdfWorkbenchMetricsSnapshot> metrics(
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(metricsService.snapshot(days));
    }

    @GetMapping("/evaluation")
    public Result<PdfWorkbenchEvalMetrics> evaluation() {
        return Result.ok(evalService.evaluate());
    }
}
