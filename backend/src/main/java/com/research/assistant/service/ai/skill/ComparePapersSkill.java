package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Comparison;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.ComparisonMapper;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.skill.io.ComparePapersInput;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 论文横向对比 Skill。
 */
@Component
public class ComparePapersSkill implements Skill<ComparePapersInput, String> {

    private static final Logger log = LoggerFactory.getLogger(ComparePapersSkill.class);

    private final PaperMapper paperMapper;
    private final PaperAnalysisMapper analysisMapper;
    private final ComparisonMapper comparisonMapper;
    private final LLMService llmService;
    private final ResearchToolAgent researchToolAgent;

    public ComparePapersSkill(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                              ComparisonMapper comparisonMapper, LLMService llmService,
                              @Lazy ResearchToolAgent researchToolAgent) {
        this.paperMapper = paperMapper;
        this.analysisMapper = analysisMapper;
        this.comparisonMapper = comparisonMapper;
        this.llmService = llmService;
        this.researchToolAgent = researchToolAgent;
    }

    @Override
    public String name() {
        return Skills.COMPARE_PAPERS;
    }

    @Override
    public String description() {
        return "对 2 篇及以上论文进行横向对比分析，返回 markdown 报告。输入：{\"paperIds\": [Long], \"customDimensions\": \"可选字符串\"}；输出：String（markdown）。";
    }

    @Override
    public Class<ComparePapersInput> inputType() {
        return ComparePapersInput.class;
    }

    @Override
    @Transactional
    public String execute(SkillContext ctx, ComparePapersInput input) {
        List<Long> paperIds = input.paperIds();
        if (paperIds == null || paperIds.size() < 2) {
            throw new RuntimeException("至少需要 2 篇论文才能对比");
        }

        ctx.stage("正在收集论文上下文…");
        String contextText = buildContext(paperIds);
        String dimensions = input.customDimensions() != null ? input.customDimensions() : "";

        ctx.stage("正在生成对比报告…");
        String result = callAgentString(
                () -> researchToolAgent.comparePapers(contextText, dimensions),
                "使用 LangChain4j Agent 完成论文对比",
                "LangChain4j 论文对比失败，回退到旧调用");
        if (result == null) {
            String systemPrompt = COMPARE_SYSTEM_PROMPT;
            if (!dimensions.isBlank()) {
                systemPrompt += "\n\n**用户指定对比维度（优先级最高）**：" + dimensions;
            }
            result = llmService.chat(systemPrompt, contextText);
        }

        Comparison comparison = new Comparison();
        comparison.setPaperIds(paperIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        comparison.setResultJson(result);
        comparisonMapper.insert(comparison);

        return result;
    }

    private String buildContext(List<Long> paperIds) {
        StringBuilder context = new StringBuilder();
        for (Long id : paperIds) {
            Paper paper = paperMapper.selectById(id);
            if (paper == null) continue;

            PaperAnalysis analysis = analysisMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                            .eq(PaperAnalysis::getPaperId, id));

            context.append("## 论文 ").append(id).append(": ").append(paper.getTitle()).append("\n");
            context.append("**年份**: ").append(paper.getYear()).append("\n");
            context.append("**来源**: ").append(paper.getSource() != null ? paper.getSource() : "未知").append("\n");

            if (analysis != null) {
                context.append("**核心贡献**: ").append(analysis.getCoreContribution()).append("\n");
                context.append("**方法类型**: ").append(analysis.getMethodType()).append("\n");
                context.append("**方法概述**: ").append(analysis.getMethodSummary()).append("\n");
                context.append("**模型**: ").append(analysis.getModelsJson()).append("\n");
                context.append("**数据集**: ").append(analysis.getDatasetsJson()).append("\n");
                context.append("**主要发现**: ").append(analysis.getKeyFindingsJson()).append("\n");
                context.append("**局限性**: ").append(analysis.getLimitationsJson()).append("\n");
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

    private static final String COMPARE_SYSTEM_PROMPT = """
你是一位资深学术研究者。请对以下多篇论文进行横向对比分析。

**第一步**：先识别这几篇论文的**共同维度和差异点**，确定最具分析价值的对比角度。

**第二步**：按识别出的维度生成对比报告（markdown，含表格）。参考维度：
- 研究问题差异 / 方法论异同 / 实验设置与数据集 / 性能对比（如可比较）/ 各自优势与局限 / 改进方向

**输出要求**：
- markdown 格式，包含表格
- 深度分析差异背后的原因，不只罗列事实
- 如信息不足，诚实说明
- 如用户指定了自定义维度，优先使用用户指定的维度
""";
}
