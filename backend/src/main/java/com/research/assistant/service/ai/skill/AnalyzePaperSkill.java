package com.research.assistant.service.ai.skill;

import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.memory.PaperAnalysisProjectionService;
import com.research.assistant.service.memory.PaperMemoryService;
import com.research.assistant.service.memory.PaperUnderstandingResult;
import com.research.assistant.service.memory.PaperUnderstandingService;
import com.research.assistant.service.rag.RagIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 论文精读 Skill：建立结构事实，分块理解，汇总全局画像并投影兼容分析。
 */
@Component
public class AnalyzePaperSkill implements Skill<Long, PaperAnalysis> {

    private static final Logger log = LoggerFactory.getLogger(AnalyzePaperSkill.class);

    private final PaperMapper paperMapper;
    private final PaperMemoryService paperMemoryService;
    private final PaperUnderstandingService understandingService;
    private final PaperAnalysisProjectionService projectionService;
    private final RagIndexingService ragIndexingService;

    public AnalyzePaperSkill(PaperMapper paperMapper,
                             PaperMemoryService paperMemoryService,
                             PaperUnderstandingService understandingService,
                             PaperAnalysisProjectionService projectionService,
                             RagIndexingService ragIndexingService) {
        this.paperMapper = paperMapper;
        this.paperMemoryService = paperMemoryService;
        this.understandingService = understandingService;
        this.projectionService = projectionService;
        this.ragIndexingService = ragIndexingService;
    }

    @Override
    public String name() {
        return Skills.ANALYZE_PAPER;
    }

    @Override
    public String description() {
        return "对单篇论文进行版面解析、分块理解与全局画像生成。输入：{\"paperId\": Long}；输出：PaperAnalysis。";
    }

    @Override
    public Class<Long> inputType() {
        return Long.class;
    }

    @Override
    public PaperAnalysis execute(SkillContext ctx, Long paperId) {
        ctx.stage("正在准备论文解析…");
        updateStatus(paperId, ProcessingStatus.PROCESSING);

        try {
            ctx.stage("正在解析 PDF 版面并建立论文结构…");
            paperMemoryService.ensureStructure(paperId, false);
            PaperUnderstandingResult understanding = understandingService.understand(
                    paperId, false, ctx::stage);
            if (!understanding.usable()) {
                throw new IllegalStateException("论文理解未产生可用分块摘要");
            }
            ctx.stage("正在保存论文全局画像…");
            PaperAnalysis analysis = projectionService.project(paperId, understanding);
            updateStatus(paperId, ProcessingStatus.COMPLETED);
            indexForRag(paperId);
            ctx.stage("分析完成");
            return analysis;
        } catch (Exception e) {
            log.error("论文处理失败: paperId={}", paperId, e);
            updateStatus(paperId, ProcessingStatus.FAILED);
            throw new RuntimeException("论文处理失败: " + e.getMessage(), e);
        }
    }

    private void updateStatus(Long paperId, String status) {
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setProcessingStatus(status);
        paperMapper.updateById(paper);
    }

    private void indexForRag(Long paperId) {
        try {
            ragIndexingService.indexPaper(paperId);
            log.info("论文 {} RAG 索引完成", paperId);
        } catch (Exception e) {
            log.warn("论文 {} RAG 索引失败，不影响主流程: {}", paperId, e.getMessage());
        }
    }
}
