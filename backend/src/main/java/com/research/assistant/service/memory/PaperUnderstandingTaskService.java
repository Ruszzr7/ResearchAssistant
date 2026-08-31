package com.research.assistant.service.memory;

import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** Application task that prepares one paper for the assistant. It is not an Agent Skill. */
@Service
public class PaperUnderstandingTaskService {

    private static final Logger log = LoggerFactory.getLogger(PaperUnderstandingTaskService.class);

    private final PaperMapper paperMapper;
    private final PaperMemoryService paperMemoryService;
    private final PaperUnderstandingService understandingService;
    private final PaperAnalysisProjectionService projectionService;

    public PaperUnderstandingTaskService(PaperMapper paperMapper,
                                         PaperMemoryService paperMemoryService,
                                         PaperUnderstandingService understandingService,
                                         PaperAnalysisProjectionService projectionService) {
        this.paperMapper = paperMapper;
        this.paperMemoryService = paperMemoryService;
        this.understandingService = understandingService;
        this.projectionService = projectionService;
    }

    public PaperAnalysis process(long paperId, Consumer<String> stageUpdater) {
        Consumer<String> stage = stageUpdater == null ? ignored -> { } : stageUpdater;
        stage.accept("正在准备论文解析…");
        updateStatus(paperId, ProcessingStatus.PROCESSING);
        try {
            stage.accept("正在解析 PDF 版面并建立论文结构…");
            paperMemoryService.ensureStructure(paperId, false);
            PaperUnderstandingResult understanding = understandingService.understand(paperId, false, stage);
            if (!understanding.usable()) {
                throw new IllegalStateException("论文理解未产生可用结果");
            }
            stage.accept("正在保存论文全局画像…");
            PaperAnalysis analysis = projectionService.project(paperId, understanding);
            updateStatus(paperId, ProcessingStatus.COMPLETED);
            stage.accept("分析完成");
            return analysis;
        } catch (Exception error) {
            log.error("论文处理失败: paperId={}", paperId, error);
            updateStatus(paperId, ProcessingStatus.FAILED);
            throw new IllegalStateException("论文处理失败: " + error.getMessage(), error);
        }
    }

    private void updateStatus(long paperId, String status) {
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setProcessingStatus(status);
        paperMapper.updateById(paper);
    }
}
