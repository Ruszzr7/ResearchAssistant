package com.research.assistant.service.ai.skill;

import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.PaperProcessingService;
import com.research.assistant.service.rag.RagIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 论文精读 Skill：触发 PDF 提取、文本清洗、LLM 结构化分析并持久化。
 */
@Component
public class AnalyzePaperSkill implements Skill<Long, PaperAnalysis> {

    private static final Logger log = LoggerFactory.getLogger(AnalyzePaperSkill.class);

    private final PaperMapper paperMapper;
    private final PaperProcessingService processingService;
    private final RagIndexingService ragIndexingService;

    public AnalyzePaperSkill(PaperMapper paperMapper, PaperProcessingService processingService,
                             RagIndexingService ragIndexingService) {
        this.paperMapper = paperMapper;
        this.processingService = processingService;
        this.ragIndexingService = ragIndexingService;
    }

    @Override
    public String name() {
        return Skills.ANALYZE_PAPER;
    }

    @Override
    public String description() {
        return "对单篇论文进行 PDF 提取与结构化精读分析。输入：{\"paperId\": Long}；输出：PaperAnalysis。";
    }

    @Override
    public Class<Long> inputType() {
        return Long.class;
    }

    @Override
    @Transactional
    public PaperAnalysis execute(SkillContext ctx, Long paperId) {
        ctx.stage("正在提取 PDF 文本…");
        updateStatus(paperId, ProcessingStatus.PROCESSING);

        try {
            PaperAnalysis analysis = processingService.process(paperId);
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
