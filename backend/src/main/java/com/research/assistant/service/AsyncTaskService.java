package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import com.research.assistant.service.memory.PaperUnderstandingTaskService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务服务 —— 替代裸 {@code new Thread()}，统一使用 Spring 线程池。
 * <p>
 * 包含论文 AI 处理与 arXiv PDF 下载等耗时任务。
 * <p>
 * 注意：本服务只注入 Mapper，不注入 PaperService，以避免循环依赖。
 */
@Service
public class AsyncTaskService {

    public static final String TASK_PROCESS_PAPER = "process-paper";
    public static final String TASK_DOWNLOAD_ARXIV = "download-arxiv-pdf";

    private final PaperUnderstandingTaskService paperUnderstandingTaskService;
    private final ArxivFetcher arxivFetcher;
    private final PaperMapper paperMapper;
    private final PaperAssetLifecycleService paperAssetLifecycleService;
    private final AsyncTaskManager asyncTaskManager;
    private final AsyncTaskHandlerRegistry handlerRegistry;

    @Value("${app.storage.pdf-dir:../data/papers}")
    private String pdfStorageDir;

    public AsyncTaskService(PaperUnderstandingTaskService paperUnderstandingTaskService,
                            ArxivFetcher arxivFetcher,
                            PaperMapper paperMapper,
                            PaperAssetLifecycleService paperAssetLifecycleService,
                            AsyncTaskManager asyncTaskManager,
                            AsyncTaskHandlerRegistry handlerRegistry) {
        this.paperUnderstandingTaskService = paperUnderstandingTaskService;
        this.arxivFetcher = arxivFetcher;
        this.paperMapper = paperMapper;
        this.paperAssetLifecycleService = paperAssetLifecycleService;
        this.asyncTaskManager = asyncTaskManager;
        this.handlerRegistry = handlerRegistry;
        registerHandlers();
    }

    @PostConstruct
    void registerHandlers() {
        registerIfAbsent(TASK_PROCESS_PAPER, context -> {
            context.stage("正在分析论文…");
            paperUnderstandingTaskService.process(
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
            Paper existing = paperMapper.selectById(paperId);
            if (existing == null) {
                throw new AsyncTaskExecutionException("PAPER_NOT_FOUND", "论文不存在", false);
            }
            String fileName = null;
            try {
                fileName = arxivFetcher.downloadPdf(arxivId, dir.getAbsolutePath());
                if (fileName == null || paperAssetLifecycleService.resolveStoredPdf(fileName) == null) {
                    throw new AsyncTaskExecutionException("ARXIV_DOWNLOAD_EMPTY", "arXiv PDF 下载未返回有效文件", false);
                }
                Paper update = new Paper();
                update.setId(paperId);
                update.setPdfPath(fileName);
                if (paperMapper.updateById(update) != 1) {
                    throw new IllegalStateException("论文记录更新失败");
                }
                if (existing.getPdfPath() != null && !existing.getPdfPath().isBlank()
                        && !existing.getPdfPath().equals(fileName)) {
                    Paper replaced = new Paper();
                    replaced.setId(paperId);
                    replaced.setPdfPath(existing.getPdfPath());
                    paperAssetLifecycleService.deleteAfterCommit(List.of(replaced));
                }
                return Map.of("paperId", paperId, "fileName", fileName);
            } catch (RuntimeException exception) {
                if (fileName != null) paperAssetLifecycleService.deleteImmediately(fileName);
                throw exception;
            }
        });
    }

    private void registerIfAbsent(String taskType, com.research.assistant.service.async.AsyncTaskHandler handler) {
        if (!handlerRegistry.contains(taskType)) {
            handlerRegistry.register(taskType, handler);
        }
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
