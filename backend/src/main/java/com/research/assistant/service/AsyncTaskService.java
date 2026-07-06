package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 异步任务服务 —— 替代裸 {@code new Thread()}，统一使用 Spring 线程池。
 * <p>
 * 包含：论文 AI 处理、arXiv PDF 下载等耗时任务。
 * <p>
 * 注意：本服务只注入 Mapper，不注入 PaperService，以避免循环依赖。
 */
@Service
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AgentOrchestrator agentOrchestrator;
    private final ArxivFetcher arxivFetcher;
    private final PaperMapper paperMapper;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public AsyncTaskService(AgentOrchestrator agentOrchestrator,
                            ArxivFetcher arxivFetcher,
                            PaperMapper paperMapper) {
        this.agentOrchestrator = agentOrchestrator;
        this.arxivFetcher = arxivFetcher;
        this.paperMapper = paperMapper;
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
}

