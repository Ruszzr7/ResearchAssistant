package com.research.assistant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.common.Result;
import com.research.assistant.dto.AgentFolderSuggestRequest;
import com.research.assistant.dto.AgentPaperIdRequest;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.ResearchAutomationService;
import com.research.assistant.service.async.AsyncTaskResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Non-conversational automation still used by the library and task center. */
@RestController
@RequestMapping("/api/research-automation")
public class ResearchAutomationController {

    private final ResearchAutomationService automationService;
    private final PaperAnalysisMapper analysisMapper;
    private final AsyncTaskService asyncTaskService;

    public ResearchAutomationController(ResearchAutomationService automationService,
                                        PaperAnalysisMapper analysisMapper,
                                        AsyncTaskService asyncTaskService) {
        this.automationService = automationService;
        this.analysisMapper = analysisMapper;
        this.asyncTaskService = asyncTaskService;
    }

    @PostMapping("/process/{paperId}")
    public Result<Map<String, Object>> process(
            @PathVariable Long paperId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String taskId = asyncTaskService.submitProcessPaper(paperId, idempotencyKey);
        return Result.ok(Map.of("paperId", paperId, "status", "PROCESSING", "taskId", taskId));
    }

    @GetMapping("/analysis/{paperId}")
    public Result<PaperAnalysis> getAnalysis(@PathVariable Long paperId) {
        PaperAnalysis analysis = analysisMapper.selectOne(new LambdaQueryWrapper<PaperAnalysis>()
                .eq(PaperAnalysis::getPaperId, paperId));
        return Result.ok(analysis);
    }

    @GetMapping("/tasks")
    public Result<List<AsyncTaskResult<?>>> listTasks(@RequestParam(required = false) Integer limit) {
        return Result.ok(asyncTaskService.listRecent(limit));
    }

    @GetMapping("/task/{taskId}")
    public Result<AsyncTaskResult<?>> getTask(@PathVariable String taskId) {
        AsyncTaskResult<?> result = asyncTaskService.getTask(taskId);
        return result == null ? Result.error(404, "任务不存在") : Result.ok(result);
    }

    @PostMapping("/task/{taskId}/cancel")
    public Result<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        return Result.ok(Map.of("cancelled", asyncTaskService.cancelTask(taskId)));
    }

    @DeleteMapping("/task/{taskId}")
    public Result<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        if (!asyncTaskService.deleteTask(taskId)) {
            return Result.error(409, "只能删除已完成、失败或已取消的任务");
        }
        return Result.ok(Map.of("deleted", true));
    }

    @PostMapping("/tag-suggestions")
    public Result<List<String>> suggestTags(@RequestBody @Valid AgentPaperIdRequest request) {
        return Result.ok(automationService.suggestTags(request.getPaperId()));
    }

    @PostMapping("/folder-suggest")
    public Result<Map<String, Object>> suggestFolder(@RequestBody @Valid AgentFolderSuggestRequest request) {
        if (request.getPaperId() != null) {
            return Result.ok(automationService.suggestFolder(request.getPaperId()));
        }
        String title = request.getTitle();
        if (title == null || title.isBlank()) {
            return Result.error(400, "请提供 paperId 或 title");
        }
        return Result.ok(automationService.suggestFolderByTitle(
                title, request.getAbstractText(), request.getKeywords()));
    }
}
