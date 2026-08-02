package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ai.plan.Plan;
import com.research.assistant.service.ai.plan.PlanExecutor;
import com.research.assistant.service.ai.plan.Planner;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskExecutionContext;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import com.research.assistant.service.rag.RagIndexingService;
import com.research.assistant.service.rag.RagIndexingException;
import com.research.assistant.service.rag.RagIndexingResult;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务服务 —— 替代裸 {@code new Thread()}，统一使用 Spring 线程池。
 * <p>
 * 包含：论文 AI 处理、arXiv PDF 下载、论文对比、Gap 分析等耗时任务。
 * <p>
 * 注意：本服务只注入 Mapper，不注入 PaperService，以避免循环依赖。
 */
@Service
public class AsyncTaskService {

    public static final String TASK_COMPARE = "compare-papers";
    public static final String TASK_GAP_PAPERS = "gap-analysis-papers";
    public static final String TASK_GAP_FOLDER = "gap-analysis-folder";
    public static final String TASK_PLAN = "natural-language-plan";
    public static final String TASK_RAG_INDEX = "rag-index";
    public static final String TASK_PROCESS_PAPER = "process-paper";
    public static final String TASK_DOWNLOAD_ARXIV = "download-arxiv-pdf";

    private final AgentOrchestrator agentOrchestrator;
    private final ArxivFetcher arxivFetcher;
    private final PaperMapper paperMapper;
    private final AsyncTaskManager asyncTaskManager;
    private final Planner planner;
    private final PlanExecutor planExecutor;
    private final RagIndexingService ragIndexingService;
    private final AsyncTaskHandlerRegistry handlerRegistry;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public AsyncTaskService(@Lazy AgentOrchestrator agentOrchestrator,
                            ArxivFetcher arxivFetcher,
                            PaperMapper paperMapper,
                            AsyncTaskManager asyncTaskManager,
                            Planner planner,
                            PlanExecutor planExecutor,
                            RagIndexingService ragIndexingService,
                            AsyncTaskHandlerRegistry handlerRegistry) {
        this.agentOrchestrator = agentOrchestrator;
        this.arxivFetcher = arxivFetcher;
        this.paperMapper = paperMapper;
        this.asyncTaskManager = asyncTaskManager;
        this.planner = planner;
        this.planExecutor = planExecutor;
        this.ragIndexingService = ragIndexingService;
        this.handlerRegistry = handlerRegistry;
        registerHandlers();
    }

    @PostConstruct
    void registerHandlers() {
        registerIfAbsent(TASK_COMPARE, context -> {
            context.stage("正在生成对比报告…");
            List<Long> paperIds = longList(context.arguments().get("paperIds"));
            return agentOrchestrator.comparePapers(paperIds,
                    (String) context.arguments().get("customDimensions"));
        });
        registerIfAbsent(TASK_GAP_PAPERS, context -> runGap(context,
                () -> agentOrchestrator.analyzeGapsByPaperIds(longList(context.arguments().get("paperIds")))));
        registerIfAbsent(TASK_GAP_FOLDER, context -> runGap(context,
                () -> agentOrchestrator.analyzeGaps(toLong(context.arguments().get("folderId")))));
        registerIfAbsent(TASK_PLAN, context -> {
            context.stage("正在规划任务…");
            String goal = (String) context.arguments().get("goal");
            Plan plan = planner.plan(goal);
            if (plan.steps().isEmpty()) {
                return "当前目标无法匹配任何可用 Skill，请更具体地描述。";
            }
            context.stage("计划已生成，开始执行…");
            return planExecutor.execute(plan,
                    new SkillContext(context.taskId(), context::stage));
        });
        registerIfAbsent(TASK_RAG_INDEX, context -> {
            context.stage("正在生成本地文本索引…");
            Long paperId = toLong(context.arguments().get("paperId"));
            try {
                RagIndexingResult result = ragIndexingService.indexPaper(paperId);
                return Map.of("paperId", result.paperId(), "indexed", result.indexed(),
                        "chunkCount", result.chunkCount());
            } catch (RagIndexingException e) {
                boolean retryable = e.getReason() == RagIndexingException.Reason.INDEX_VERSION_FAILED;
                throw new AsyncTaskExecutionException(e.getReason().name(), e.getMessage(), retryable, e);
            }
        });
        registerIfAbsent(TASK_PROCESS_PAPER, context -> {
            context.stage("正在分析论文…");
            agentOrchestrator.processPaper(
                    toLong(context.arguments().get("paperId")), context::stage);
            return Map.of("paperId", toLong(context.arguments().get("paperId")), "processed", true);
        });
        registerIfAbsent(TASK_DOWNLOAD_ARXIV, context -> {
            Long paperId = toLong(context.arguments().get("paperId"));
            String arxivId = (String) context.arguments().get("arxivId");
            context.stage("正在下载 arXiv PDF…");
            File dir = new File(pdfStorageDir);
            if (!dir.isAbsolute()) {
                dir = new File(System.getProperty("user.dir"), pdfStorageDir);
            }
            String fileName = arxivFetcher.downloadPdf(arxivId, dir.getAbsolutePath());
            if (fileName == null) {
                throw new AsyncTaskExecutionException("ARXIV_DOWNLOAD_EMPTY", "arXiv PDF 下载未返回文件", false);
            }
            Paper update = new Paper();
            update.setId(paperId);
            update.setPdfPath(fileName);
            paperMapper.updateById(update);
            processPaperAsync(paperId);
            return Map.of("paperId", paperId, "fileName", fileName);
        });
    }

    private void registerIfAbsent(String taskType, com.research.assistant.service.async.AsyncTaskHandler handler) {
        if (!handlerRegistry.contains(taskType)) {
            handlerRegistry.register(taskType, handler);
        }
    }

    private Object runGap(AsyncTaskExecutionContext context, java.util.function.Supplier<String> supplier) {
        context.stage("正在分析研究空白…");
        String gaps = supplier.get();
        context.stage("正在进行外部验证…");
        return Map.of("gaps", gaps, "verified", agentOrchestrator.verifyGaps(gaps));
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : values) {
            Long number = toLong(item);
            if (number != null) {
                result.add(number);
            }
        }
        return result;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 异步触发单篇论文的 AI 深度分析。
     */
    public void processPaperAsync(Long paperId) {
        submitRecoverable(TASK_PROCESS_PAPER, paperId, null);
    }

    public String submitProcessPaper(Long paperId, String idempotencyKey) {
        return submitRecoverable(TASK_PROCESS_PAPER, paperId, idempotencyKey);
    }

    /**
     * 异步下载 arXiv PDF 并更新论文 pdfPath。
     * <p>
     * 下载成功后自动触发 AI 分析（参见 P1.3）。
     */
    public void downloadArxivPdfAsync(Long paperId, String arxivId) {
        downloadArxivPdfAsync(paperId, arxivId, null);
    }

    public String downloadArxivPdfAsync(Long paperId, String arxivId, String idempotencyKey) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("paperId", paperId);
        arguments.put("arxivId", arxivId);
        return asyncTaskManager.submitRecoverable(TASK_DOWNLOAD_ARXIV, null,
                "arXiv PDF 下载 (paperId=" + paperId + ")", arguments, idempotencyKey);
    }

    private String submitRecoverable(String taskType, Long paperId, String idempotencyKey) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("paperId", paperId);
        return asyncTaskManager.submitRecoverable(taskType, null, "论文处理 (paperId=" + paperId + ")", arguments, idempotencyKey);
    }

    /**
     * 提交异步论文对比任务。
     *
     * @return 任务 ID
     */
    public String submitComparePapers(List<Long> paperIds, String customDimensions) {
        return submitComparePapers(paperIds, customDimensions, null);
    }

    public String submitComparePapers(List<Long> paperIds, String customDimensions, String idempotencyKey) {
        String title = "论文对比 (" + (paperIds != null ? paperIds.size() : 0) + " 篇)";
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("paperIds", paperIds == null ? List.of() : paperIds);
        arguments.put("customDimensions", customDimensions);
        return asyncTaskManager.submitRecoverable(TASK_COMPARE, null, title, arguments, idempotencyKey);
    }

    /**
     * 提交异步 Gap 分析任务（库内分析 + 外部验证）。
     *
     * @return 任务 ID
     */
    public String submitGapAnalysis(List<Long> paperIds) {
        return submitGapAnalysis(paperIds, null);
    }

    public String submitGapAnalysis(List<Long> paperIds, String idempotencyKey) {
        String title = "Gap 分析 (" + (paperIds != null ? paperIds.size() : 0) + " 篇)";
        return asyncTaskManager.submitRecoverable(TASK_GAP_PAPERS, null, title,
                Map.of("paperIds", paperIds == null ? List.of() : paperIds), idempotencyKey);
    }

    /**
     * 提交异步文件夹 Gap 分析任务。
     *
     * @return 任务 ID
     */
    public String submitGapAnalysisByFolder(Long folderId) {
        return submitGapAnalysisByFolder(folderId, null);
    }

    public String submitGapAnalysisByFolder(Long folderId, String idempotencyKey) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("folderId", folderId);
        return asyncTaskManager.submitRecoverable(TASK_GAP_FOLDER, null,
                "Gap 分析 (文件夹 " + folderId + ")", arguments, idempotencyKey);
    }

    /**
     * 提交自然语言规划任务：LLM 先选择 Skill 生成计划，再自动执行。
     *
     * @return 任务 ID
     */
    public String submitPlan(String goal) {
        return submitPlan(goal, null);
    }

    public String submitPlan(String goal, String idempotencyKey) {
        String title = "智能规划";
        if (goal != null) {
            title += ": " + (goal.length() > 30 ? goal.substring(0, 30) + "…" : goal);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", goal);
        return asyncTaskManager.submitRecoverable(TASK_PLAN, null, title, arguments, idempotencyKey);
    }

    /**
     * 提交 RAG 索引异步任务。
     *
     * @return 任务 ID
     */
    public String submitRagIndex(Long paperId) {
        String title = "RAG 索引 (paperId=" + paperId + ")";
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("paperId", paperId);
        return asyncTaskManager.submitRecoverable(TASK_RAG_INDEX, null, title,
                arguments, null);
    }

    /**
     * 查询最近的异步任务列表。
     *
     * @param limit 最多返回条数，默认 50
     * @return 任务结果列表
     */
    public List<AsyncTaskResult<?>> listRecent(Integer limit) {
        int size = limit != null && limit > 0 ? limit : 50;
        return asyncTaskManager.listRecent(size);
    }

    /**
     * 查询异步任务状态与结果。
     */
    public AsyncTaskResult<?> getTask(String taskId) {
        return asyncTaskManager.get(taskId);
    }

    /**
     * 取消异步任务。
     */
    public boolean cancelTask(String taskId) {
        return asyncTaskManager.cancel(taskId);
    }

    /**
     * 删除已终态的异步任务。
     *
     * @return true 表示删除成功
     */
    public boolean deleteTask(String taskId) {
        return asyncTaskManager.delete(taskId);
    }
}
