package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.ChatRequest;
import com.research.assistant.dto.CompareRequest;
import com.research.assistant.dto.GapRequest;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.AgentOrchestrator;
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
 * Agent REST 接口 —— 论文处理、对比、Gap 分析的统一入口。
 * <p>
 * 处理接口为异步模式：触发后立即返回，前端通过 processingStatus 轮询进度。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator agentOrchestrator;
    private final PaperAnalysisMapper analysisMapper;
    private final ArxivFetcher arxivFetcher;
    private final LLMService llmService;
    private final AsyncTaskService asyncTaskService;

    public AgentController(AgentOrchestrator agentOrchestrator, PaperAnalysisMapper analysisMapper,
                           ArxivFetcher arxivFetcher, LLMService llmService,
                           AsyncTaskService asyncTaskService) {
        this.agentOrchestrator = agentOrchestrator;
        this.analysisMapper = analysisMapper;
        this.arxivFetcher = arxivFetcher;
        this.llmService = llmService;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * POST /api/agent/process/{paperId} — 启动论文深度分析（异步）。
     */
    @PostMapping("/process/{paperId}")
    public Result<Map<String, Object>> process(@PathVariable Long paperId) {
        asyncTaskService.processPaperAsync(paperId);
        return Result.ok(Map.of("paperId", paperId, "status", "PROCESSING"));
    }

    /**
     * GET /api/agent/process/{paperId}/stream — 流式论文精读分析（SSE）。
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
     * POST /api/agent/gap/stream — 流式 Gap 分析（SSE）。
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
     * GET /api/agent/analysis/{paperId} — 获取论文分析结果。
     */
    @GetMapping("/analysis/{paperId}")
    public Result<PaperAnalysis> getAnalysis(@PathVariable Long paperId) {
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        return Result.ok(analysis);
    }

    /**
     * POST /api/agent/plan — 自然语言任务规划：LLM 自动选择 Skill 并执行。
     */
    @PostMapping("/plan")
    public Result<Map<String, String>> plan(@RequestBody Map<String, String> body) {
        String goal = body.get("goal");
        if (goal == null || goal.isBlank()) {
            return Result.error(400, "请提供 goal");
        }
        String taskId = asyncTaskService.submitPlan(goal);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/agent/compare — 提交横向对比异步任务。
     */
    @PostMapping("/compare")
    public Result<Map<String, String>> compare(@RequestBody @Valid CompareRequest request) {
        String taskId = asyncTaskService.submitComparePapers(
                request.getPaperIds(), request.getCustomDimensions());
        return Result.ok(Map.of("taskId", taskId));
    }

    /** POST /api/agent/chat — 分析追问对话（支持多轮记忆） */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody @Valid ChatRequest request) {
        String result = agentOrchestrator.chatAbout(
                request.getConversationId(), request.getContext(), request.getQuestion());
        return Result.ok(result);
    }

    /**
     * POST /api/agent/gap/folder/{folderId} — 提交基于文件夹的 Gap 分析异步任务。
     */
    @PostMapping("/gap/folder/{folderId}")
    public Result<Map<String, String>> gapByFolder(@PathVariable Long folderId) {
        String taskId = asyncTaskService.submitGapAnalysisByFolder(folderId);
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * POST /api/agent/gap — 提交 Gap 分析异步任务（库内分析 + 外部验证）。
     */
    @PostMapping("/gap")
    public Result<Map<String, String>> gap(@RequestBody @Valid GapRequest request) {
        String taskId = asyncTaskService.submitGapAnalysis(request.getPaperIds());
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * GET /api/agent/tasks — 查询最近的异步任务列表。
     */
    @GetMapping("/tasks")
    public Result<List<AsyncTaskResult<?>>> listTasks(@RequestParam(required = false) Integer limit) {
        return Result.ok(asyncTaskService.listRecent(limit));
    }

    /**
     * GET /api/agent/task/{taskId} — 查询异步任务状态与结果。
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
     * POST /api/agent/task/{taskId}/cancel — 取消异步任务。
     */
    @PostMapping("/task/{taskId}/cancel")
    public Result<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean cancelled = asyncTaskService.cancelTask(taskId);
        return Result.ok(Map.of("cancelled", cancelled));
    }

    /** POST /api/agent/gap/internal — Step 1: 库内 Gap 分析 */
    @PostMapping("/gap/internal")
    public Result<Map<String, Object>> gapInternal(@RequestBody @Valid GapRequest request) {
        String result = agentOrchestrator.analyzeGapsByPaperIds(request.getPaperIds());
        return Result.ok(Map.of("gaps", result));
    }

    /**
     * POST /api/agent/gap/verify — Step 2: 外部验证。
     * 由 Agent 调用 arXiv/Crossref/PDF 提取等工具综合判断每个 Gap 是否已被研究。
     */
    @PostMapping("/gap/verify")
    public Result<List<Map<String, Object>>> gapVerify(@RequestBody Map<String, Object> body) {
        String gaps = (String) body.get("gaps");
        if (gaps == null || gaps.isBlank()) {
            return Result.error(400, "请提供库内 Gap 分析结果");
        }
        return Result.ok(agentOrchestrator.verifyGaps(gaps));
    }

    /** POST /api/agent/gap/chat — Gap 追问（支持多轮记忆） */
    @PostMapping("/gap/chat")
    public Result<String> gapChat(@RequestBody @Valid ChatRequest request) {
        String result = agentOrchestrator.chatAbout(
                request.getConversationId(), request.getContext(), request.getQuestion());
        return Result.ok(result);
    }

    /**
     * POST /api/agent/search — 文献检索（arXiv API）。
     */
    @PostMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.error(400, "搜索关键词不能为空");
        }
        int maxResults = body.containsKey("maxResults") ? ((Number) body.get("maxResults")).intValue() : 20;
        try {
            List<Map<String, Object>> results = arxivFetcher.search(query, maxResults);
            return Result.ok(results);
        } catch (Exception e) {
            return Result.error(500, "检索失败: " + e.getMessage());
        }
    }

    @PostMapping("/tag-suggestions")
    public Result<List<String>> suggestTags(@RequestBody Map<String, Long> body) {
        return Result.ok(agentOrchestrator.suggestTags(body.get("paperId")));
    }

    @PostMapping("/folder-suggest")
    public Result<Map<String, Object>> suggestFolder(@RequestBody Map<String, Object> body) {
        // 支持两种模式：已有论文传 paperId，导入时传 title + folders
        if (body.containsKey("paperId") && body.get("paperId") != null) {
            Long paperId = Long.valueOf(body.get("paperId").toString());
            return Result.ok(agentOrchestrator.suggestFolder(paperId));
        }
        // 导入时：基于标题和现有文件夹推荐
        String title = (String) body.get("title");
        if (title == null || title.isBlank()) {
            return Result.error(400, "请提供 paperId 或 title");
        }
        return Result.ok(agentOrchestrator.suggestFolderByTitle(title));
    }

    @PostMapping("/reading-status-suggest")
    public Result<Map<String, Object>> suggestReadingStatus(@RequestBody Map<String, Long> body) {
        Long paperId = body.get("paperId");
        if (paperId == null) {
            return Result.error(400, "请提供 paperId");
        }
        return Result.ok(agentOrchestrator.suggestReadingStatus(paperId));
    }
}
