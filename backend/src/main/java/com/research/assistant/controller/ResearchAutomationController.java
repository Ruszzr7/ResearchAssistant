package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.CompareRequest;
import com.research.assistant.dto.GapRequest;
import com.research.assistant.dto.AgentFolderSuggestRequest;
import com.research.assistant.dto.AgentGapVerifyRequest;
import com.research.assistant.dto.AgentPaperIdRequest;
import com.research.assistant.dto.AgentPlanRequest;
import com.research.assistant.dto.AgentSearchRequest;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.ResearchAutomationService;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.async.AsyncTaskResult;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非对话式研究自动化接口。对话 Agent 只由 AgentTurnController 提供。
 * <p>
 * 处理接口为异步模式：触发后立即返回，前端通过 processingStatus 轮询进度。
 */
@RestController
@RequestMapping("/api/research-automation")
public class ResearchAutomationController {

    private final ResearchAutomationService automationService;
    private final PaperAnalysisMapper analysisMapper;
    private final ArxivFetcher arxivFetcher;
    private final LLMService llmService;
    private final AsyncTaskService asyncTaskService;

    public ResearchAutomationController(ResearchAutomationService automationService, PaperAnalysisMapper analysisMapper,
                           ArxivFetcher arxivFetcher, LLMService llmService,
                           AsyncTaskService asyncTaskService) {
        this.automationService = automationService;
        this.analysisMapper = analysisMapper;
        this.arxivFetcher = arxivFetcher;
        this.llmService = llmService;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * POST /api/research-automation/process/{paperId} — 启动论文理解（异步）。
     */
    @PostMapping("/process/{paperId}")
    public Result<Map<String, Object>> process(@PathVariable Long paperId,
                                               @RequestHeader(value = "Idempotency-Key", required = false)
                                               String idempotencyKey) {
        String taskId = asyncTaskService.submitProcessPaper(paperId, idempotencyKey);
        return Result.ok(Map.of("paperId", paperId, "status", "PROCESSING", "taskId", taskId));
    }

    /**
     * GET /api/research-automation/process/{paperId}/stream — 兼容的流式分析输出。
     * 需要论文已做过 AI 分析（有 rawText）。
     */
    @GetMapping(value = "/process/{paperId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody processStream(@PathVariable Long paperId, HttpServletResponse response) {
        prepareSseResponse(response);
        // 获取已分析文本
        PaperAnalysis existing = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        String rawText = existing != null ? existing.getRawText() : null;
        if (rawText == null || rawText.isBlank()) {
            return out -> {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                writer.write("event:error\ndata:请先触发 AI 分析\n\n");
                writer.flush();
            };
        }
        return llmService.chatStream(
                "你是资深学术审稿人。阅读论文，输出 markdown 结构化分析："
                        + "先判断领域（AI/CV/NLP/通信/控制），再分析核心贡献、方法概述（模型/算法/框架）、"
                        + "使用的数据集和模型、主要发现、局限性。",
                rawText);
    }

    /**
     * POST /api/research-automation/gap/stream — 流式 Gap 分析（SSE）。
     */
    @PostMapping(value = "/gap/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody gapStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        prepareSseResponse(response);
        @SuppressWarnings("unchecked")
        List<Long> paperIds = (List<Long>) body.get("paperIds");
        if (paperIds == null || paperIds.size() < 3) {
            return out -> {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                writer.write("event:error\ndata:至少需要 3 篇论文进行 Gap 分析\n\n");
                writer.flush();
            };
        }
        // 构建上下文
        StringBuilder context = new StringBuilder("# 领域论文集合\n\n");
        for (Long id : paperIds) {
            PaperAnalysis a = analysisMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                            .eq(PaperAnalysis::getPaperId, id));
            if (a != null) {
                context.append("## Paper ").append(id).append("\n");
                context.append("**核心贡献**: ").append(a.getCoreContribution()).append("\n");
                context.append("**方法概述**: ").append(a.getMethodSummary()).append("\n");
                context.append("**局限性**: ").append(a.getLimitationsJson()).append("\n\n---\n");
            }
        }
        return llmService.chatStream(
                "你是资深学术研究者。对论文集合进行 Gap 分析。维度：方法Gap/场景数据Gap/理论Gap/比较Gap/交叉Gap。"
                        + "每个Gap含标题(如'[方法Gap] xxx')、描述、为什么是Gap、潜在价值(高/中/低)、可行方向。至少3个。",
                context.toString());
    }

    /** 禁用 SSE 响应缓冲，确保 token 实时推送 */
    private void prepareSseResponse(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setBufferSize(0);
    }

    /**
     * GET /api/research-automation/analysis/{paperId} — 获取论文分析结果。
     */
    @GetMapping("/analysis/{paperId}")
    public Result<PaperAnalysis> getAnalysis(@PathVariable Long paperId) {
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        return Result.ok(analysis);
    }

    /**
     * POST /api/research-automation/plan — 提交自然语言自动化任务。
     */
    @PostMapping("/plan")
    public Result<Map<String, String>> plan(@RequestBody @Valid AgentPlanRequest request,
                                            @RequestHeader(value = "Idempotency-Key", required = false)
                                            String idempotencyKey) {
        String goal = request.getGoal();
        if (goal == null || goal.isBlank()) {
            return Result.error(400, "请提供 goal");
        }
        String taskId = asyncTaskService.submitPlan(goal, idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/research-automation/compare — 提交横向对比异步任务。
     */
    @PostMapping("/compare")
    public Result<Map<String, String>> compare(@RequestBody @Valid CompareRequest request,
                                               @RequestHeader(value = "Idempotency-Key", required = false)
                                               String idempotencyKey) {
        String taskId = asyncTaskService.submitComparePapers(
                request.getPaperIds(), request.getCustomDimensions(), idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/research-automation/gap/folder/{folderId} — 提交基于文件夹的 Gap 分析异步任务。
     */
    @PostMapping("/gap/folder/{folderId}")
    public Result<Map<String, String>> gapByFolder(@PathVariable Long folderId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey) {
        String taskId = asyncTaskService.submitGapAnalysisByFolder(folderId, idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/research-automation/gap — 提交 Gap 分析异步任务（库内分析 + 外部验证）。
     */
    @PostMapping("/gap")
    public Result<Map<String, String>> gap(@RequestBody @Valid GapRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                          String idempotencyKey) {
        String taskId = asyncTaskService.submitGapAnalysis(request.getPaperIds(), idempotencyKey);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * GET /api/research-automation/tasks — 查询最近的异步任务列表。
     */
    @GetMapping("/tasks")
    public Result<List<AsyncTaskResult<?>>> listTasks(@RequestParam(required = false) Integer limit) {
        return Result.ok(asyncTaskService.listRecent(limit));
    }

    /**
     * GET /api/research-automation/task/{taskId} — 查询异步任务状态与结果。
     */
    @GetMapping("/task/{taskId}")
    public Result<AsyncTaskResult<?>> getTask(@PathVariable String taskId) {
        AsyncTaskResult<?> result = asyncTaskService.getTask(taskId);
        if (result == null) {
            return Result.error(404, "任务不存在");
        }
        return Result.ok(result);
    }

    /**
     * POST /api/research-automation/task/{taskId}/cancel — 取消异步任务。
     */
    @PostMapping("/task/{taskId}/cancel")
    public Result<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean cancelled = asyncTaskService.cancelTask(taskId);
        return Result.ok(Map.of("cancelled", cancelled));
    }

    /**
     * DELETE /api/research-automation/task/{taskId} — 删除已终态的异步任务。
     */
    @DeleteMapping("/task/{taskId}")
    public Result<Map<String, Object>> deleteTask(@PathVariable String taskId) {
        boolean deleted = asyncTaskService.deleteTask(taskId);
        if (!deleted) {
            return Result.error(409, "只能删除已完成、失败或已取消的任务");
        }
        return Result.ok(Map.of("deleted", true));
    }

    /** POST /api/research-automation/gap/internal — Step 1: 库内 Gap 分析 */
    @PostMapping("/gap/internal")
    public Result<Map<String, Object>> gapInternal(@RequestBody @Valid GapRequest request) {
        String result = automationService.analyzeGapsByPaperIds(request.getPaperIds());
        return Result.ok(Map.of("gaps", result));
    }

    /**
     * POST /api/research-automation/gap/verify — Step 2: 外部验证。
     * 由 Agent 调用 arXiv/Crossref/PDF 提取等工具综合判断每个 Gap 是否已被研究。
     */
    @PostMapping("/gap/verify")
    public Result<List<Map<String, Object>>> gapVerify(@RequestBody @Valid AgentGapVerifyRequest request) {
        String gaps = request.getGaps();
        if (gaps == null || gaps.isBlank()) {
            return Result.error(400, "请提供库内 Gap 分析结果");
        }
        return Result.ok(automationService.verifyGaps(gaps));
    }

    /**
     * POST /api/research-automation/search — 文献检索（arXiv API）。
     */
    @PostMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestBody @Valid AgentSearchRequest request) {
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return Result.error(400, "搜索关键词不能为空");
        }
        int maxResults = request.getMaxResults() == null ? 20 : request.getMaxResults();
        try {
            List<Map<String, Object>> results = arxivFetcher.search(query, maxResults);
            return Result.ok(results);
        } catch (Exception e) {
            return Result.error(502, "外部文献检索暂时不可用，请稍后重试");
        }
    }

    @PostMapping("/tag-suggestions")
    public Result<List<String>> suggestTags(@RequestBody @Valid AgentPaperIdRequest request) {
        return Result.ok(automationService.suggestTags(request.getPaperId()));
    }

    @PostMapping("/folder-suggest")
    public Result<Map<String, Object>> suggestFolder(@RequestBody @Valid AgentFolderSuggestRequest request) {
        // 支持两种模式：已有论文传 paperId，导入时传 title + folders
        if (request.getPaperId() != null) {
            return Result.ok(automationService.suggestFolder(request.getPaperId()));
        }
        // 导入时：基于标题和现有文件夹推荐
        String title = request.getTitle();
        if (title == null || title.isBlank()) {
            return Result.error(400, "请提供 paperId 或 title");
        }
        return Result.ok(automationService.suggestFolderByTitle(
                title, request.getAbstractText(), request.getKeywords()));
    }

    @PostMapping("/reading-status-suggest")
    public Result<Map<String, Object>> suggestReadingStatus(@RequestBody @Valid AgentPaperIdRequest request) {
        Long paperId = request.getPaperId();
        if (paperId == null) {
            return Result.error(400, "请提供 paperId");
        }
        return Result.ok(automationService.suggestReadingStatus(paperId));
    }
}
