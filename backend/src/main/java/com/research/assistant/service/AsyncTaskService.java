package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ai.plan.Plan;
import com.research.assistant.service.ai.plan.PlanExecutor;
import com.research.assistant.service.ai.plan.Planner;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.rag.RagIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
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

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AgentOrchestrator agentOrchestrator;
    private final ArxivFetcher arxivFetcher;
    private final PaperMapper paperMapper;
    private final AsyncTaskManager asyncTaskManager;
    private final Planner planner;
    private final PlanExecutor planExecutor;
    private final RagIndexingService ragIndexingService;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public AsyncTaskService(@Lazy AgentOrchestrator agentOrchestrator,
                            ArxivFetcher arxivFetcher,
                            PaperMapper paperMapper,
                            AsyncTaskManager asyncTaskManager,
                            Planner planner,
                            PlanExecutor planExecutor,
                            RagIndexingService ragIndexingService) {
        this.agentOrchestrator = agentOrchestrator;
        this.arxivFetcher = arxivFetcher;
        this.paperMapper = paperMapper;
        this.asyncTaskManager = asyncTaskManager;
        this.planner = planner;
        this.planExecutor = planExecutor;
        this.ragIndexingService = ragIndexingService;
    }

    /**
     * 异步触发单篇论文的 AI 深度分析。
     */
    @Async("taskExecutor")
    public void processPaperAsync(Long paperId) {
        try {
            agentOrchestrator.processPaper(paperId);
        } catch (Exception e) {
            log.warn("异步分析失败 paperId={}: {}", paperId, e.getMessage());
        }
    }

    /**
     * 异步下载 arXiv PDF 并更新论文 pdfPath。
     * <p>
     * 下载成功后自动触发 AI 分析（参见 P1.3）。
     */
    @Async("taskExecutor")
    public void downloadArxivPdfAsync(Long paperId, String arxivId) {
        try {
            File dir = new File(pdfStorageDir);
            if (!dir.isAbsolute()) {
                dir = new File(System.getProperty("user.dir"), pdfStorageDir);
            }
            String fileName = arxivFetcher.downloadPdf(arxivId, dir.getAbsolutePath());
            if (fileName != null) {
                Paper update = new Paper();
                update.setId(paperId);
                update.setPdfPath(fileName);
                paperMapper.updateById(update);
                // 下载完成后自动触发 AI 处理
                processPaperAsync(paperId);
            }
        } catch (Exception e) {
            log.warn("后台 PDF 下载失败 paperId={} arxivId={}: {}", paperId, arxivId, e.getMessage());
        }
    }

    /**
     * 提交异步论文对比任务。
     *
     * @return 任务 ID
     */
    public String submitComparePapers(List<Long> paperIds, String customDimensions) {
        String title = "论文对比 (" + (paperIds != null ? paperIds.size() : 0) + " 篇)";
        return asyncTaskManager.submit(title, setStage -> {
            setStage.accept("正在生成对比报告…");
            return agentOrchestrator.comparePapers(paperIds, customDimensions);
        });
    }

    /**
     * 提交异步 Gap 分析任务（库内分析 + 外部验证）。
     *
     * @return 任务 ID
     */
    public String submitGapAnalysis(List<Long> paperIds) {
        String title = "Gap 分析 (" + (paperIds != null ? paperIds.size() : 0) + " 篇)";
        return submitGap(() -> agentOrchestrator.analyzeGapsByPaperIds(paperIds), title);
    }

    /**
     * 提交异步文件夹 Gap 分析任务。
     *
     * @return 任务 ID
     */
    public String submitGapAnalysisByFolder(Long folderId) {
        return submitGap(() -> agentOrchestrator.analyzeGaps(folderId), "Gap 分析 (文件夹 " + folderId + ")");
    }

    private String submitGap(java.util.function.Supplier<String> gapsSupplier, String title) {
        return asyncTaskManager.submit(title, setStage -> {
            setStage.accept("正在分析研究空白…");
            String gaps = gapsSupplier.get();
            setStage.accept("正在进行外部验证…");
            List<Map<String, Object>> verified = agentOrchestrator.verifyGaps(gaps);
            return Map.of("gaps", gaps, "verified", verified);
        });
    }

    /**
     * 提交自然语言规划任务：LLM 先选择 Skill 生成计划，再自动执行。
     *
     * @return 任务 ID
     */
    public String submitPlan(String goal) {
        String title = "智能规划";
        if (goal != null) {
            title += ": " + (goal.length() > 30 ? goal.substring(0, 30) + "…" : goal);
        }
        return asyncTaskManager.submit(title, setStage -> {
            setStage.accept("正在规划任务…");
            Plan plan = planner.plan(goal);

            if (plan.steps().isEmpty()) {
                return "当前目标无法匹配任何可用 Skill，请更具体地描述。";
            }

            setStage.accept("计划已生成，开始执行…");
            Object result = planExecutor.execute(plan, new SkillContext(null, setStage));
            return result;
        });
    }

    /**
     * 提交 RAG 索引异步任务。
     *
     * @return 任务 ID
     */
    public String submitRagIndex(Long paperId) {
        String title = "RAG 索引 (paperId=" + paperId + ")";
        return asyncTaskManager.submit(title, setStage -> {
            setStage.accept("正在生成向量索引…");
            ragIndexingService.indexPaper(paperId);
            return Map.of("paperId", paperId, "indexed", true);
        });
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
}

