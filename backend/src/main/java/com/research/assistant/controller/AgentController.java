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
     * POST /api/agent/compare — 横向对比多篇论文。
     */
    @PostMapping("/compare")
    public Result<String> compare(@RequestBody @Valid CompareRequest request) {
        String result = agentOrchestrator.comparePapers(request.getPaperIds(), request.getCustomDimensions());
        return Result.ok(result);
    }

    /** POST /api/agent/chat — 分析追问对话（支持多轮记忆） */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody @Valid ChatRequest request) {
        String result = agentOrchestrator.chatAbout(
                request.getConversationId(), request.getContext(), request.getQuestion());
        return Result.ok(result);
    }

    /**
     * POST /api/agent/gap/folder/{folderId} — 基于文件夹的 Gap 分析（库内 + 外部验证）。
     */
    @PostMapping("/gap/folder/{folderId}")
    public Result<Map<String, Object>> gapByFolder(@PathVariable Long folderId) {
        String gapReport = agentOrchestrator.analyzeGaps(folderId);
        List<Map<String, Object>> verified = verifyGaps(gapReport);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gaps", gapReport);
        result.put("verified", verified);
        return Result.ok(result);
    }

    /**
     * POST /api/agent/gap — Gap 分析（一站式：库内分析 + 外部验证）。
     */
    @PostMapping("/gap")
    public Result<Map<String, Object>> gap(@RequestBody @Valid GapRequest request) {
        List<Long> paperIds = request.getPaperIds();
        // Step 1: 库内分析
        String gapReport = agentOrchestrator.analyzeGapsByPaperIds(paperIds);
        // Step 2: 外部验证（对提取的 gap 逐一检索）
        List<Map<String, Object>> verified = verifyGaps(gapReport);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("gaps", gapReport);
        result.put("verified", verified);
        return Result.ok(result);
    }

    /** POST /api/agent/gap/internal — Step 1: 库内 Gap 分析 */
    @PostMapping("/gap/internal")
    public Result<Map<String, Object>> gapInternal(@RequestBody @Valid GapRequest request) {
        String result = agentOrchestrator.analyzeGapsByPaperIds(request.getPaperIds());
        return Result.ok(Map.of("gaps", result));
    }

    /**
     * POST /api/agent/gap/verify — Step 2: 外部验证。
     * 对每个 Gap 标题生成检索关键词，搜索 arXiv，返回验证等级和证据。
     */
    @PostMapping("/gap/verify")
    public Result<List<Map<String, Object>>> gapVerify(@RequestBody Map<String, Object> body) {
        String gaps = (String) body.get("gaps");
        if (gaps == null || gaps.isBlank()) {
            return Result.error(400, "请提供库内 Gap 分析结果");
        }
        return Result.ok(verifyGaps(gaps));
    }

    /**
     * 对 Gap 报告中的每个 Gap 条目进行外部验证。
     * 策略：从标题中提取关键词 → arXiv 搜索 → 根据搜索结果数量判断验证等级。
     */
    private List<Map<String, Object>> verifyGaps(String gapReport) {
        List<Map<String, Object>> verified = new ArrayList<>();
        // 按 "Gap N:" 或 "### " 标题分割
        String[] parts = gapReport.split("(?=###\\s+|Gap\\s*\\d)");
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            // 提取标题（第一行）
            String firstLine = part.split("\\n")[0].trim();
            // 移除 markdown 标记
            String gapTitle = firstLine.replaceAll("^#+\\s*", "")
                    .replaceAll("[🔴🟡🟢]", "").trim();
            if (gapTitle.isEmpty()) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gapTitle", gapTitle);
            try {
                // 用 Gap 标题的前 5 个词作为搜索关键词
                String[] words = gapTitle.split("\\s+");
                String query = String.join(" ", java.util.Arrays.copyOf(words, Math.min(5, words.length)));
                List<Map<String, Object>> results = arxivFetcher.search(query, 3);
                int count = results.size();
                item.put("resultCount", count);
                item.put("level", count == 0 ? "red" : count <= 1 ? "yellow" : "green");
                item.put("label", count == 0 ? "未发现相关研究" : count <= 1 ? "有少量相关工作" : "已有较多相关研究");
                // 搜索深度说明
                item.put("searchDepth", "摘要级搜索（arXiv API max_results=3），未检索付费墙后正文");
                item.put("searchQuery", query);
                if (!results.isEmpty()) {
                    item.put("sampleTitle", results.get(0).get("title"));
                }
            } catch (Exception e) {
                item.put("resultCount", 0);
                item.put("level", "yellow");
                item.put("label", "外部验证失败: " + e.getMessage());
                item.put("searchDepth", "验证过程出错，无法评估");
            }
            verified.add(item);
        }
        return verified;
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
}
