package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.ResearchSynthesisQualityException;
import com.research.assistant.service.ai.ResearchSynthesisQualityGate;
import com.research.assistant.service.ai.skill.io.AnalyzeGapsInput;
import com.research.assistant.service.rag.RagRetrievalService;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.function.Supplier;

/**
 * 库内 Gap 分析 Skill。
 */
@Component
public class AnalyzeGapsSkill implements Skill<AnalyzeGapsInput, String> {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeGapsSkill.class);

    private final PaperMapper paperMapper;
    private final PaperAnalysisMapper analysisMapper;
    private final LLMService llmService;
    private final ResearchToolAgent researchToolAgent;
    private final RagRetrievalService ragRetrievalService;
    private final ResearchSynthesisQualityGate qualityGate;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyzeGapsSkill(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                            LLMService llmService, @Lazy ResearchToolAgent researchToolAgent,
                            RagRetrievalService ragRetrievalService,
                            ResearchSynthesisQualityGate qualityGate) {
        this.paperMapper = paperMapper;
        this.analysisMapper = analysisMapper;
        this.llmService = llmService;
        this.researchToolAgent = researchToolAgent;
        this.ragRetrievalService = ragRetrievalService;
        this.qualityGate = qualityGate;
    }

    public AnalyzeGapsSkill(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                            LLMService llmService, @Lazy ResearchToolAgent researchToolAgent,
                            RagRetrievalService ragRetrievalService) {
        this(paperMapper, analysisMapper, llmService, researchToolAgent, ragRetrievalService,
                new ResearchSynthesisQualityGate());
    }

    @Override
    public String name() {
        return Skills.ANALYZE_GAPS;
    }

    @Override
    public String description() {
        return "基于论文列表或文件夹识别研究空白（Gap）。输入：{\"paperIds\": [Long] 或 \"folderId\": Long（为 null 则全库分析）}；输出：String（markdown 报告）。";
    }

    @Override
    public Class<AnalyzeGapsInput> inputType() {
        return AnalyzeGapsInput.class;
    }

    @Override
    public String execute(SkillContext ctx, AnalyzeGapsInput input) {
        List<Paper> papers = loadPapers(input);
        if (papers.isEmpty()) {
            throw new RuntimeException("未找到所选论文");
        }

        ctx.stage("正在构建论文上下文…");
        String contextText = buildGapContext(papers);

        ctx.stage("正在分析研究空白…");
        String result = callAgentString(
                () -> researchToolAgent.analyzeGaps(contextText),
                "使用 LangChain4j Agent 完成 Gap 分析",
                "LangChain4j Gap 分析失败，回退到旧调用");
        if (result == null) {
            result = llmService.chat(GAP_SYSTEM_PROMPT, contextText);
        }
        ResearchSynthesisQualityGate.QualityReport quality = qualityGate.validateGaps(result);
        if (!quality.valid()) {
            log.warn("gap report rejected: issues={}", quality.issues());
            result = llmService.chat(GAP_SYSTEM_PROMPT, contextText);
            quality = qualityGate.validateGaps(result);
        }
        if (!quality.valid()) {
            throw new ResearchSynthesisQualityException("gap", quality.issues());
        }
        return quality.content();
    }

    private List<Paper> loadPapers(AnalyzeGapsInput input) {
        if (input.paperIds() != null && !input.paperIds().isEmpty()) {
            return paperMapper.selectBatchIds(input.paperIds());
        }
        if (input.folderId() == null) {
            return paperMapper.selectList(null);
        }
        return paperMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Paper>()
                        .eq(Paper::getFolderId, input.folderId()));
    }

    private String buildGapContext(List<Paper> papers) {
        StringBuilder context = new StringBuilder();
        context.append("# 领域论文集合（共 ").append(papers.size()).append(" 篇）\n\n");
        for (Paper paper : papers) {
            PaperAnalysis analysis = analysisMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                            .eq(PaperAnalysis::getPaperId, paper.getId()));

            context.append("## ").append(paper.getTitle()).append("\n");
            context.append("**年份**: ").append(paper.getYear()).append("\n");
            if (analysis != null) {
                context.append("**核心贡献**: ").append(analysis.getCoreContribution()).append("\n");
                context.append("**方法类型**: ").append(analysis.getMethodType()).append("\n");
                context.append("**方法概述**: ").append(analysis.getMethodSummary()).append("\n");
                context.append("**局限性**: ").append(analysis.getLimitationsJson()).append("\n");

                String query = paper.getTitle() + "\n" + (analysis.getCoreContribution() != null ? analysis.getCoreContribution() : "");
                String relatedSnippets = ragRetrievalService.retrieveAndRerankAsContext(query, 5, 0.65);
                if (!relatedSnippets.isBlank()) {
                    context.append("**相关片段**: ").append(relatedSnippets).append("\n");
                }
            }
            context.append("\n---\n");
        }
        return context.toString();
    }

    private String callAgentString(Supplier<Result<String>> caller, String successLog, String fallbackLog) {
        try {
            Result<String> result = caller.get();
            if (result != null && result.content() != null) {
                log.info(successLog);
                return result.content();
            }
        } catch (Exception e) {
            log.warn("{}: {}", fallbackLog, e.getMessage());
        }
        return null;
    }

    private static final String GAP_SYSTEM_PROMPT = """
你是一位资深学术研究者，正在对一个研究领域进行系统性文献综述。

**任务**：阅读以下论文集合的信息，识别该领域的研究空白（Research Gaps）和未来方向。

**分析维度**（每个 Gap 必须归类到以下维度之一）：
1. **方法 Gap**：主流方法/算法有什么已知缺陷？有没有被忽视的替代思路？
2. **场景/数据 Gap**：现有实验在什么条件下做的？什么场景没人研究过？
3. **理论 Gap**：哪些结论缺乏理论证明？方法背后的理论基础是否薄弱？
4. **比较 Gap**：几篇论文的方法有没有被公平比较过？缺乏统一的 benchmark？
5. **交叉 Gap**：有没有其他领域的方法可以迁移过来？跨领域的技术能否适用？

**输出格式**（markdown，每个 Gap 包含）：
- ### [维度标签] Gap 标题
  - **描述**: Gap 是什么
  - **为什么是 Gap**: 现有方法为什么没解决
  - **潜在价值**: 高/中/低
  - **可行方向**: 建议的研究思路
  - **验证建议**: 推荐用什么关键词去 arXiv 验证这个 Gap 是否已被研究

至少输出 3 个 Gap，覆盖至少 3 个不同维度。对每个 Gap 诚实评估其不确定性。
""";
}
