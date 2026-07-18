package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.memory.PaperMemoryQueryService;
import com.research.assistant.service.memory.PaperMemoryStatusView;
import com.research.assistant.service.memory.PaperUnderstandingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Starts and observes the asynchronous whole-paper understanding pipeline. */
@RestController
@RequestMapping("/api/papers/{paperId}/memory")
public class PaperMemoryController {

    private final PaperMemoryQueryService queryService;
    private final AsyncTaskService asyncTaskService;

    public PaperMemoryController(PaperMemoryQueryService queryService,
                                 AsyncTaskService asyncTaskService) {
        this.queryService = queryService;
        this.asyncTaskService = asyncTaskService;
    }

    @GetMapping
    public Result<PaperMemoryStatusView> status(@PathVariable Long paperId) {
        return Result.ok(queryService.status(paperId));
    }

    @PostMapping("/understand")
    public Result<Map<String, Object>> understand(
            @PathVariable Long paperId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaperMemoryStatusView current = queryService.status(paperId);
        if (PaperUnderstandingService.STATUS_UNDERSTANDING.equals(current.status())) {
            return Result.ok(Map.of("paperId", paperId, "status", current.status(), "alreadyRunning", true));
        }
        if (!current.canStart()) {
            return Result.ok(Map.of("paperId", paperId, "status", current.status(), "alreadyReady", true));
        }
        String effectiveKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "paper-memory:" + paperId + ":" + current.memoryId() + ":" + current.revision()
                : idempotencyKey;
        String taskId = asyncTaskService.submitProcessPaper(paperId, effectiveKey);
        return Result.ok(Map.of(
                "paperId", paperId, "status", "PROCESSING", "taskId", taskId));
    }
}
