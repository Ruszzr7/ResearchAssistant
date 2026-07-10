package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.service.ai.workflow.WorkflowService;
import com.research.assistant.service.async.AsyncTaskResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工作流相关接口。
 */
@RestController
@RequestMapping("/api/agent/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * POST /api/agent/workflow/gap-research — 提交 Gap Research 工作流。
     */
    @PostMapping("/gap-research")
    public Result<Map<String, String>> gapResearch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> paperIds = (List<Long>) body.get("paperIds");
        if (paperIds == null || paperIds.size() < 3) {
            return Result.error(400, "至少需要 3 篇论文进行 Gap 分析");
        }
        String taskId = workflowService.submitGapResearch(paperIds);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/agent/workflow/paper-import — 提交论文入库流水线。
     */
    @PostMapping("/paper-import")
    public Result<Map<String, String>> paperImport(@RequestBody Map<String, Object> body) {
        Object paperIdObj = body.get("paperId");
        if (paperIdObj == null) {
            return Result.error(400, "请提供 paperId");
        }
        Long paperId = ((Number) paperIdObj).longValue();
        String taskId = workflowService.submitPaperImport(paperId);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/agent/workflow/literature-survey — 提交文献调研工作流。
     */
    @PostMapping("/literature-survey")
    public Result<Map<String, String>> literatureSurvey(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.error(400, "请提供检索目标 query");
        }
        String taskId = workflowService.submitLiteratureSurvey(query);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/agent/workflow/{taskId}/confirm — 用户确认后继续工作流。
     */
    @PostMapping("/{taskId}/confirm")
    public Result<Map<String, String>> confirm(@PathVariable String taskId,
                                             @RequestBody Map<String, Object> body) {
        String newTaskId = workflowService.confirm(taskId, body);
        return Result.ok(Map.of("taskId", newTaskId));
    }

    /**
     * GET /api/agent/workflow/{taskId} — 查询工作流任务状态与结果。
     */
    @GetMapping("/{taskId}")
    public Result<AsyncTaskResult<?>> get(@PathVariable String taskId) {
        AsyncTaskResult<?> result = workflowService.get(taskId);
        if (result == null) {
            return Result.error(404, "任务不存在");
        }
        return Result.ok(result);
    }

    /**
     * POST /api/agent/workflow/{taskId}/retry — 从失败点重试工作流。
     */
    @PostMapping("/{taskId}/retry")
    public Result<Map<String, String>> retry(@PathVariable String taskId) {
        String newTaskId = workflowService.retry(taskId);
        return Result.ok(Map.of("taskId", newTaskId));
    }
}
