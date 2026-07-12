package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.WorkflowConfirmRequest;
import com.research.assistant.dto.WorkflowGapResearchRequest;
import com.research.assistant.dto.WorkflowLiteratureSurveyRequest;
import com.research.assistant.dto.WorkflowPaperImportRequest;
import com.research.assistant.service.ai.workflow.WorkflowService;
import com.research.assistant.service.async.AsyncTaskResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/gap-research")
    public Result<Map<String, String>> gapResearch(@RequestBody @Valid WorkflowGapResearchRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey) {
        String taskId = workflowService.submitGapResearch(request.getPaperIds(), idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    @PostMapping("/paper-import")
    public Result<Map<String, String>> paperImport(@RequestBody @Valid WorkflowPaperImportRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey) {
        String taskId = workflowService.submitPaperImport(request.getPaperId(), idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    @PostMapping("/literature-survey")
    public Result<Map<String, String>> literatureSurvey(@RequestBody @Valid WorkflowLiteratureSurveyRequest request,
                                                        @RequestHeader(value = "Idempotency-Key", required = false)
                                                        String idempotencyKey) {
        String taskId = workflowService.submitLiteratureSurvey(request.getQuery(), idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    @PostMapping("/{taskId}/confirm")
    public Result<Map<String, String>> confirm(@PathVariable String taskId,
                                               @RequestBody @Valid WorkflowConfirmRequest request) {
        String newTaskId = workflowService.confirm(taskId, request.toUserInput());
        return Result.ok(Map.of("taskId", newTaskId));
    }

    @GetMapping("/{taskId}")
    public Result<AsyncTaskResult<?>> get(@PathVariable String taskId) {
        AsyncTaskResult<?> result = workflowService.get(taskId);
        if (result == null) return Result.error(404, "任务不存在");
        return Result.ok(result);
    }

    @PostMapping("/{taskId}/retry")
    public Result<Map<String, String>> retry(@PathVariable String taskId) {
        String newTaskId = workflowService.retry(taskId);
        return Result.ok(Map.of("taskId", newTaskId));
    }
}
