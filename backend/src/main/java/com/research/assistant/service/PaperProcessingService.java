package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ai.PaperAnalysisResult;
import com.research.assistant.service.ai.ResearchAiService;
import dev.langchain4j.service.Result;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

/**
 * 论文处理服务 —— 编排完整的 PDF → 文本 → LLM 理解 → 持久化流水线。
 * <p>
 * Phase 1 引入 LangChain4j 结构化输出：优先使用 {@link ResearchAiService#analyzePaper(String, String)}
 * 返回 POJO；若 POJO 解析失败，回退到旧的手写 JSON 字段提取。
 */
@Service
public class PaperProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaperProcessingService.class);

    private final PaperMapper paperMapper;
    private final PaperAnalysisMapper analysisMapper;
    private final PdfExtractor pdfExtractor;
    private final TextPreprocessor textPreprocessor;
    private final LLMService llmService;
    private final ResearchAiService researchAiService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final AsyncTaskService asyncTaskService;

    /** LLM 输入的最大字符数（防止 token 超限） */
    private static final int MAX_INPUT_CHARS = 12000;

    /** 用户研究主题设置 key */
    private static final String RESEARCH_TOPIC_KEY = "research_topic";

    public PaperProcessingService(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                                   PdfExtractor pdfExtractor, TextPreprocessor textPreprocessor,
                                   LLMService llmService, @Lazy ResearchAiService researchAiService,
                                   SettingsService settingsService, ObjectMapper objectMapper,
                                   AsyncTaskService asyncTaskService) {
        this.paperMapper = paperMapper;
        this.analysisMapper = analysisMapper;
        this.pdfExtractor = pdfExtractor;
        this.textPreprocessor = textPreprocessor;
        this.llmService = llmService;
        this.researchAiService = researchAiService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.asyncTaskService = asyncTaskService;
    }

    /**
     * 同步处理一篇论文（完整流水线）。
     * <p>
     * 调用方应自行处理状态更新。处理时间取决于 LLM 响应速度（通常 10-60s）。
     */
    @Transactional
    public PaperAnalysis process(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在: " + paperId);
        }

        // Step 1: 提取 PDF 文本、公式与图表
        String pdfPath = paper.getPdfPath();
        if (pdfPath == null || pdfPath.isBlank()) {
            throw new RuntimeException("论文尚未上传 PDF，无法分析: " + paperId);
        }
        String rawText = pdfExtractor.extract(pdfPath);
        if (rawText.isEmpty()) {
            throw new RuntimeException("PDF 文本提取为空，可能是扫描版 PDF: " + paperId);
        }

        // Step 2: 文本预处理
        String cleanedText = textPreprocessor.clean(rawText);
        String inputText = textPreprocessor.truncate(cleanedText, MAX_INPUT_CHARS);
        log.info("Paper {} 文本预处理完成: {} 字符 → {} 字符", paperId, rawText.length(), inputText.length());

        // Step 3: LLM 结构化理解（POJO 优先，失败回退旧解析）
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(paperId);
        analysis.setRawText(inputText);
        enrichFormulasAndFigures(analysis, pdfPath);

        String researchTopic = loadResearchTopic();
        boolean pojoSuccess = analyzeWithPojo(paperId, inputText, researchTopic, analysis);
        if (!pojoSuccess) {
            analyzeWithFallback(paperId, inputText, researchTopic, analysis);
        }

        // Step 4: 保存分析结果
        PaperAnalysis old = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        if (old != null) {
            analysisMapper.deleteById(old.getId());
        }
        analysisMapper.insert(analysis);

        // Step 5: 异步建立 RAG 向量索引
        try {
            asyncTaskService.submitRagIndex(paperId);
        } catch (Exception e) {
            log.warn("论文 {} RAG 索引任务提交失败: {}", paperId, e.getMessage());
        }

        return analysis;
    }

    private String loadResearchTopic() {
        String topic = settingsService.getValue(RESEARCH_TOPIC_KEY);
        return topic != null ? topic : "";
    }

    private void enrichFormulasAndFigures(PaperAnalysis analysis, String pdfPath) {
        try {
            analysis.setFormulasJson(toJson(pdfExtractor.extractFormulas(pdfPath)));
        } catch (Exception e) {
            log.warn("Paper {} 公式提取失败: {}", analysis.getPaperId(), e.getMessage());
            analysis.setFormulasJson("[]");
        }
        try {
            analysis.setFiguresJson(toJson(pdfExtractor.extractFigures(pdfPath)));
        } catch (Exception e) {
            log.warn("Paper {} 图表提取失败: {}", analysis.getPaperId(), e.getMessage());
            analysis.setFiguresJson("[]");
        }
    }

    /**
     * 主路径：使用 LangChain4j AiServices 获取结构化 POJO。
     *
     * @return true 表示成功，false 表示需要回退
     */
    private boolean analyzeWithPojo(Long paperId, String inputText, String researchTopic, PaperAnalysis analysis) {
        try {
            Result<PaperAnalysisResult> result = researchAiService.analyzePaper(inputText, researchTopic);
            if (result == null || result.content() == null) {
                log.warn("Paper {} POJO 分析返回 null，准备回退", paperId);
                return false;
            }
            PaperAnalysisResult pojo = result.content();
            fillFromPojo(analysis, pojo);

            TokenUsage usage = result.tokenUsage();
            int totalTokens = usage != null && usage.totalTokenCount() != null
                    ? usage.totalTokenCount() : 0;
            analysis.setTokenUsed(totalTokens);

            log.info("Paper {} 使用 LangChain4j POJO 分析完成，token: {}", paperId, totalTokens);
            return true;
        } catch (Exception e) {
            log.warn("Paper {} POJO 分析失败，准备回退: {}", paperId, e.getMessage());
            return false;
        }
    }

    /**
     * 降级路径：使用旧的手写 Prompt + 手动 JSON 字段提取。
     */
    private void analyzeWithFallback(Long paperId, String inputText, String researchTopic, PaperAnalysis analysis) {
        String userMessage = buildFallbackUserMessage(inputText, researchTopic);
        LlmResponse llmResponse = llmService.chatWithUsage(ANALYSIS_SYSTEM_PROMPT, userMessage);
        String resultJson = llmResponse.getContent();
        log.info("Paper {} LLM 分析完成（旧解析路径）, 结果长度: {}, token 消耗: {}",
                paperId, resultJson.length(), llmResponse.getTotalTokens());

        parseAndFillAnalysis(analysis, resultJson);
        analysis.setTokenUsed(llmResponse.getTotalTokens());
    }

    private String buildFallbackUserMessage(String inputText, String researchTopic) {
        return "用户当前研究主题：" + researchTopic + "\n\n请对以下论文文本进行结构化分析：\n\n" + inputText;
    }

    /**
     * 将 POJO 结果映射到 PaperAnalysis 实体。
     */
    private void fillFromPojo(PaperAnalysis analysis, PaperAnalysisResult result) {
        analysis.setCoreContribution(result.getCoreContribution());

        // 保持与旧解析一致的存储格式：domain 与 method_type 用 "|" 拼接
        String domain = result.getDomain();
        String methodType = result.getMethodType();
        if (domain != null && !domain.isBlank()) {
            analysis.setMethodType((domain + "|" + (methodType != null ? methodType : "")).trim());
        } else {
            analysis.setMethodType(methodType);
        }

        analysis.setMethodSummary(result.getMethodSummary());
        analysis.setSectionsJson(toJson(result.getSections()));
        analysis.setDatasetsJson(toJson(result.getDatasets()));
        analysis.setModelsJson(toJson(result.getModels()));
        analysis.setKeyFindingsJson(toJson(result.getKeyFindings()));
        analysis.setLimitationsJson(toJson(result.getLimitations()));
        analysis.setTablesSummaryJson(toJson(result.getTablesSummary()));
        analysis.setFiguresSummaryJson(toJson(result.getFiguresSummary()));
        analysis.setReproducibleArtifactsJson(toJson(result.getReproducibleArtifacts()));
        analysis.setExperimentSetupJson(toJson(result.getExperimentSetup()));
        analysis.setBenchmarkResultsJson(toJson(result.getBenchmarkResults()));
        analysis.setRelevanceScore(result.getRelevanceScore());
        analysis.setRelevanceReason(result.getRelevanceReason());
    }

    private String toJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 将 LLM 返回的 JSON 解析填充到 PaperAnalysis 实体。
     * <p>
     * 作为 POJO 路径的 fallback 保留。先清洗 markdown 代码块，再由 Jackson 解析，
     * 避免手写字符串扫描在转义字符或嵌套 JSON 下失效。
     */
    private void parseAndFillAnalysis(PaperAnalysis analysis, String llmOutput) {
        String json = JsonUtils.extractJson(llmOutput);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("LLM 返回的 JSON 不是对象");
            }
            String domain = textField(root, "domain");
            String methodType = textField(root, "method_type");
            analysis.setCoreContribution(textField(root, "core_contribution"));
            analysis.setMethodType(domain == null || domain.isBlank()
                    ? methodType : (domain + "|" + (methodType == null ? "" : methodType)).trim());
            analysis.setMethodSummary(textField(root, "method_summary"));
            analysis.setSectionsJson(jsonField(root, "sections"));
            analysis.setDatasetsJson(jsonField(root, "datasets"));
            analysis.setModelsJson(jsonField(root, "models"));
            analysis.setKeyFindingsJson(jsonField(root, "key_findings"));
            analysis.setLimitationsJson(jsonField(root, "limitations"));
            analysis.setTablesSummaryJson(jsonField(root, "tables_summary"));
            analysis.setFiguresSummaryJson(jsonField(root, "figures_summary"));
            analysis.setReproducibleArtifactsJson(jsonField(root, "reproducible_artifacts"));
            analysis.setExperimentSetupJson(jsonField(root, "experiment_setup"));
            analysis.setBenchmarkResultsJson(jsonField(root, "benchmark_results"));
            analysis.setRelevanceScore(nullableIntField(root, "relevance_score"));
            analysis.setRelevanceReason(textField(root, "relevance_reason"));
        } catch (Exception e) {
            log.warn("论文 {} fallback JSON 解析失败: {}", analysis.getPaperId(), e.getMessage());
            analysis.setSectionsJson("[]");
            analysis.setDatasetsJson("[]");
            analysis.setModelsJson("[]");
            analysis.setKeyFindingsJson("[]");
            analysis.setLimitationsJson("[]");
            analysis.setTablesSummaryJson("[]");
            analysis.setFiguresSummaryJson("[]");
            analysis.setReproducibleArtifactsJson("[]");
            analysis.setExperimentSetupJson("{}");
            analysis.setBenchmarkResultsJson("[]");
        }
    }

    private String textField(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() ? null : node.asText("");
    }

    private String jsonField(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() ? "[]" : node.toString();
    }

    private Integer nullableIntField(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) return null;
        if (node.isInt() || node.isLong()) return node.intValue();
        return parseNullableInt(node.asText());
    }

    private Integer parseNullableInt(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== LLM Prompt 模板 ==========

    /** 论文结构化分析的系统提示词（自适应领域） */
    private static final String ANALYSIS_SYSTEM_PROMPT = """
你是一位资深学术论文审稿人。请仔细阅读以下 PDF 提取的论文文本，完成结构化分析。

**第一步：判断领域**
先判断论文属于哪个领域，然后采用对应的分析框架：
- AI/CV/NLP：侧重模型架构、训练策略、Benchmark 提升、Ablation 实验
- 通信/信号处理：侧重系统模型、信道假设、理论推导、仿真设置
- 控制/机器人：侧重控制策略、稳定性分析、实验平台
- 其他：根据论文实际内容自适应

**第二步：深度提取**
除了基础信息外，必须尽可能提取：
1. 可复现要素：核心公式（保留 LaTeX）、伪代码、源码/数据集链接、评测指标定义
2. 实验设置：任务定义、数据集与划分、基线、评测指标、实现细节
3. Benchmark 结果：关键指标、数值、与基线对比、来源图表

**第三步：相关度评分（仅当用户研究主题非空时）**
如果用户研究主题非空，请给出 1-10 的相关度评分并说明理由。
1 = 完全无关；10 = 高度相关，可直接借鉴。

**第四步：输出 JSON**
请严格按照以下 JSON 格式（不要输出其他内容）：

```json
{
  "domain": "判断的领域（如 AI/NLP/通信/控制/其他）",
  "core_contribution": "三句话概括核心贡献（问题是什么、怎么解决的、效果如何）",
  "method_type": "THEORETICAL / EXPERIMENTAL / SYSTEM / SURVEY（四选一）",
  "method_summary": "方法概述：用了什么模型/算法/框架，关键设计是什么",
  "sections": [{"heading": "章节标题", "summary": "该章节的一句话摘要"}],
  "datasets": ["使用的数据集名称"],
  "models": ["使用的模型/算法名称"],
  "key_findings": ["主要实验发现或理论结果"],
  "limitations": ["论文自述的局限性和你推断的潜在问题"],
  "tables_summary": [{"caption": "表格标题", "content_hint": "大致内容"}],
  "figures_summary": [{"caption": "图表标题"}],
  "reproducible_artifacts": [
    {"type": "FORMULA", "title": "Eq. (4)", "content": "LaTeX 或内容", "location": "Section 3.2"}
  ],
  "experiment_setup": {
    "task_definition": "任务定义",
    "datasets": ["数据集及划分方式"],
    "baselines": ["基线方法"],
    "metrics": ["评测指标"],
    "implementation_details": "硬件、框架、超参数等"
  },
  "benchmark_results": [
    {"metric": "指标名", "value": "论文值", "baseline_value": "基线值", "dataset": "数据集", "source": "Table 3", "note": "补充说明"}
  ],
  "relevance_score": null,
  "relevance_reason": null
}
```

**规则**：
- 信息无法确定时用空数组 [] 或空字符串 ""；
- 不要编造内容；
- sections 按实际结构输出；
- 若用户研究主题为空字符串，relevance_score 和 relevance_reason 必须为 null；
- 若研究主题非空，relevance_score 填 1-10 的整数，relevance_reason 填 1-2 句话。
""";
}
