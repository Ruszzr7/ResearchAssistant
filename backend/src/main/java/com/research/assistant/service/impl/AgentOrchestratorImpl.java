package com.research.assistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Comparison;
import com.research.assistant.entity.Folder;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.ComparisonMapper;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.AgentOrchestrator;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.PaperProcessingService;
import com.research.assistant.service.ai.ResearchAiService;
import com.research.assistant.service.ai.ResearchToolAgent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 编排器实现。
 * <p>
 * 处理状态机：PENDING → PROCESSING → COMPLETED / FAILED
 */
@Service
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final PaperMapper paperMapper;
    private final PaperAnalysisMapper analysisMapper;
    private final ComparisonMapper comparisonMapper;
    private final FolderMapper folderMapper;
    private final PaperProcessingService processingService;
    private final LLMService llmService;
    private final ResearchAiService researchAiService;
    private final ResearchToolAgent researchToolAgent;
    private final ChatMemoryStore chatMemoryStore;
    private final ArxivFetcher arxivFetcher;

    public AgentOrchestratorImpl(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                                  ComparisonMapper comparisonMapper, FolderMapper folderMapper,
                                  PaperProcessingService processingService, LLMService llmService,
                                  ResearchAiService researchAiService, ResearchToolAgent researchToolAgent,
                                  ChatMemoryStore chatMemoryStore, ArxivFetcher arxivFetcher) {
        this.paperMapper = paperMapper;
        this.analysisMapper = analysisMapper;
        this.comparisonMapper = comparisonMapper;
        this.folderMapper = folderMapper;
        this.processingService = processingService;
        this.llmService = llmService;
        this.researchAiService = researchAiService;
        this.researchToolAgent = researchToolAgent;
        this.chatMemoryStore = chatMemoryStore;
        this.arxivFetcher = arxivFetcher;
    }

    @Override
    @Transactional
    public PaperAnalysis processPaper(Long paperId) {
        // 更新状态为 PROCESSING
        updateStatus(paperId, ProcessingStatus.PROCESSING);

        try {
            PaperAnalysis analysis = processingService.process(paperId);
            // 更新状态为 COMPLETED
            updateStatus(paperId, ProcessingStatus.COMPLETED);
            return analysis;
        } catch (Exception e) {
            log.error("论文处理失败: paperId={}", paperId, e);
            // 更新状态为 FAILED
            updateStatus(paperId, ProcessingStatus.FAILED);
            throw new RuntimeException("论文处理失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public String comparePapers(List<Long> paperIds, String customDimensions) {
        if (paperIds.size() < 2) {
            throw new RuntimeException("至少需要 2 篇论文才能对比");
        }

        // 收集各论文的分析结果
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

        // 自定义维度指令
        String systemPrompt = COMPARE_SYSTEM_PROMPT;
        if (customDimensions != null && !customDimensions.isBlank()) {
            systemPrompt += "\n\n**用户指定对比维度（优先级最高）**：" + customDimensions;
        }

        String result = llmService.chat(systemPrompt, context.toString());

        // 保存对比记录
        Comparison comparison = new Comparison();
        comparison.setPaperIds(paperIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        comparison.setResultJson(result);
        comparisonMapper.insert(comparison);

        return result;
    }

    @Override
    public String analyzeGaps(Long folderId) {
        // 读取论文列表
        List<Paper> papers;
        if (folderId == null) {
            papers = paperMapper.selectList(null);
        } else {
            papers = paperMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Paper>()
                            .eq(Paper::getFolderId, folderId));
        }

        if (papers.isEmpty()) {
            throw new RuntimeException("该文件夹下没有论文");
        }

        // 收集所有论文的分析结果
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
            }
            context.append("\n---\n");
        }

        return llmService.chat(GAP_SYSTEM_PROMPT, context.toString());
    }

    @Override
    public String analyzeGapsByPaperIds(List<Long> paperIds) {
        List<Paper> papers = paperMapper.selectBatchIds(paperIds);
        if (papers.isEmpty()) {
            throw new RuntimeException("未找到所选论文");
        }

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
            }
            context.append("\n---\n");
        }

        return llmService.chat(GAP_SYSTEM_PROMPT, context.toString());
    }

    @Override
    public List<Map<String, Object>> verifyGaps(String gapReport) {
        if (gapReport == null || gapReport.isBlank()) {
            return List.of();
        }

        try {
            Result<String> result = researchToolAgent.verifyGaps(gapReport);
            String json = result != null ? result.content() : "";
            if (json != null && !json.isBlank()) {
                json = extractJson(json);
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> verified = mapper.readValue(json, List.class);
                for (Map<String, Object> item : verified) {
                    item.put("searchDepth", "LLM 工具调用：arXiv/Crossref/PDF 提取综合验证");
                }
                return verified;
            }
        } catch (Exception e) {
            log.warn("Agent Gap 验证失败，将回退到手动规则验证", e);
        }

        return verifyGapsManually(gapReport);
    }

    /**
     * 旧版手动 Gap 验证：按标题关键词拆分 → arXiv 搜索 → 按结果数量分级。
     */
    private List<Map<String, Object>> verifyGapsManually(String gapReport) {
        List<Map<String, Object>> verified = new ArrayList<>();
        String[] parts = gapReport.split("(?=###\\s+|Gap\\s*\\d)");
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            String firstLine = part.split("\\n")[0].trim();
            String gapTitle = firstLine.replaceAll("^#+\\s*", "")
                    .replaceAll("[🔴🟡🟢]", "").trim();
            if (gapTitle.isEmpty()) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gapTitle", gapTitle);
            try {
                String[] words = gapTitle.split("\\s+");
                String query = String.join(" ", java.util.Arrays.copyOf(words, Math.min(5, words.length)));
                List<Map<String, Object>> results = arxivFetcher.search(query, 3);
                int count = results.size();
                item.put("resultCount", count);
                item.put("level", count == 0 ? "red" : count <= 1 ? "yellow" : "green");
                item.put("label", count == 0 ? "未发现相关研究" : count <= 1 ? "有少量相关工作" : "已有较多相关研究");
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

    @Override
    public String chatAbout(String context, String question) {
        String prompt = "以下是之前的分析上下文：\n\n" + context + "\n\n"
                + "用户提问：" + question + "\n\n"
                + "请基于上下文回答用户的问题。如果上下文中没有相关信息，诚实说明。";
        return llmService.chat(
                "你是一位学术研究助手，帮助用户深入理解论文分析结果。回答简洁专业。",
                prompt);
    }

    @Override
    public String chatAbout(String conversationId, String context, String question) {
        if (conversationId == null || conversationId.isBlank()) {
            return chatAbout(context, question);
        }

        // 如果是该会话的第一条消息，先把角色 + 上下文作为 system message 写入记忆
        List<ChatMessage> messages = chatMemoryStore.getMessages(conversationId);
        if (messages.isEmpty()) {
            String systemContent = """
                    你是一位学术研究助手，帮助用户深入理解论文分析结果。回答简洁专业；
                    如果上下文中没有相关信息，诚实说明。
                    """;
            if (context != null && !context.isBlank()) {
                systemContent += "\n\n以下是对论文分析结果的上下文：\n\n" + context;
            }
            chatMemoryStore.updateMessages(conversationId, List.of(SystemMessage.from(systemContent)));
        }

        Result<String> result = researchAiService.chat(conversationId, question);
        return result != null ? result.content() : "";
    }

    @Override
    public List<String> suggestTags(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) return Collections.emptyList();
        String prompt = "论文标题：" + paper.getTitle() + "\n摘要：" +
                (paper.getAbstractText() != null ? paper.getAbstractText() : "无") +
                "\n\n请为这篇论文建议 3-5 个标签（技术关键词），用逗号分隔，只返回标签列表。";
        String result = llmService.chat("你是一位学术文献分类专家。为论文建议精准的分类标签。", prompt);
        return Arrays.stream(result.split("[，,]+")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> suggestFolder(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) return Map.of("recommended", null, "suggestNew", false);
        return doSuggestFolder(paper.getTitle());
    }

    @Override
    public Map<String, Object> suggestFolderByTitle(String title) {
        return doSuggestFolder(title);
    }

    /** 通用文件夹推荐逻辑 */
    private Map<String, Object> doSuggestFolder(String title) {
        List<Folder> folders = folderMapper.selectList(null);
        if (folders.isEmpty()) return Map.of("recommended", null, "suggestNew", true, "newName", "新文件夹");

        StringBuilder folderList = new StringBuilder();
        for (Folder f : folders) {
            folderList.append("- ").append(f.getName()).append(" (id=").append(f.getId()).append(")\n");
        }
        String prompt = "论文标题：" + title + "\n现有文件夹列表：\n" + folderList +
                "\n请为这篇论文推荐最合适的现有文件夹。返回 JSON: {\"folderId\": 数字 或 null, \"reason\": \"一句话理由\", \"suggestNew\": true/false, \"newName\": \"建议新文件夹名（若 suggestNew 为 true）\"}";
        String result = llmService.chat("你是一位学术文献管理助手。请为论文推荐最合适的文件夹。只返回JSON。", prompt);
        try {
            return new ObjectMapper().readValue(extractJson(result), Map.class);
        } catch (Exception e) {
            return Map.of("recommended", null, "suggestNew", false);
        }
    }

    private String extractJson(String s) {
        s = s.trim();
        if (s.startsWith("```")) { int i = s.indexOf('\n'), j = s.lastIndexOf("```"); if (i > 0 && j > i) s = s.substring(i + 1, j).trim(); }
        return s;
    }

    /** 更新论文的处理状态 */
    private void updateStatus(Long paperId, String status) {
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setProcessingStatus(status);
        paperMapper.updateById(paper);
    }

    // ========== Prompt 模板 ==========

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
