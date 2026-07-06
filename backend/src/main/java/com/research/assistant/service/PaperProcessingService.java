package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 论文处理服务 —— 编排完整的 PDF → 文本 → LLM 理解 → 持久化流水线。
 * <p>
 * Phase 1 引入 LangChain4j 结构化输出：优先使用 {@link ResearchAiService#analyzePaper(String)}
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
    private final ObjectMapper objectMapper;

    /** LLM 输入的最大字符数（防止 token 超限） */
    private static final int MAX_INPUT_CHARS = 12000;

    public PaperProcessingService(PaperMapper paperMapper, PaperAnalysisMapper analysisMapper,
                                   PdfExtractor pdfExtractor, TextPreprocessor textPreprocessor,
                                   LLMService llmService, ResearchAiService researchAiService,
                                   ObjectMapper objectMapper) {
        this.paperMapper = paperMapper;
        this.analysisMapper = analysisMapper;
        this.pdfExtractor = pdfExtractor;
        this.textPreprocessor = textPreprocessor;
        this.llmService = llmService;
        this.researchAiService = researchAiService;
        this.objectMapper = objectMapper;
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

        // Step 1: 提取 PDF 文本
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

        boolean pojoSuccess = analyzeWithPojo(paperId, inputText, analysis);
        if (!pojoSuccess) {
            analyzeWithFallback(paperId, inputText, analysis);
        }

        // Step 4: 保存分析结果
        PaperAnalysis old = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        if (old != null) {
            analysisMapper.deleteById(old.getId());
        }
        analysisMapper.insert(analysis);

        return analysis;
    }

    /**
     * 主路径：使用 LangChain4j AiServices 获取结构化 POJO。
     *
     * @return true 表示成功，false 表示需要回退
     */
    private boolean analyzeWithPojo(Long paperId, String inputText, PaperAnalysis analysis) {
        try {
            Result<PaperAnalysisResult> result = researchAiService.analyzePaper(inputText);
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
    private void analyzeWithFallback(Long paperId, String inputText, PaperAnalysis analysis) {
        LlmResponse llmResponse = llmService.chatWithUsage(ANALYSIS_SYSTEM_PROMPT, inputText);
        String resultJson = llmResponse.getContent();
        log.info("Paper {} LLM 分析完成（旧解析路径）, 结果长度: {}, token 消耗: {}",
                paperId, resultJson.length(), llmResponse.getTotalTokens());

        parseAndFillAnalysis(analysis, resultJson);
        analysis.setTokenUsed(llmResponse.getTotalTokens());
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
     * 作为 POJO 路径的 fallback 保留。使用简单的字符串解析而非 Jackson 反序列化——
     * LLM 返回的 JSON 可能含 markdown 代码块标记，需要先清洗。
     */
    private void parseAndFillAnalysis(PaperAnalysis analysis, String llmOutput) {
        String json = extractJson(llmOutput);

        // domain 字段暂存到 methodType 前缀，如 "AI|THEORETICAL"
        String domain = extractField(json, "domain");
        String methodType = extractField(json, "method_type");
        analysis.setCoreContribution(extractField(json, "core_contribution"));
        if (domain != null && !domain.isBlank()) {
            analysis.setMethodType((domain + "|" + (methodType != null ? methodType : "")).trim());
        } else {
            analysis.setMethodType(methodType);
        }
        analysis.setMethodSummary(extractField(json, "method_summary"));
        analysis.setSectionsJson(extractField(json, "sections"));
        analysis.setDatasetsJson(extractField(json, "datasets"));
        analysis.setModelsJson(extractField(json, "models"));
        analysis.setKeyFindingsJson(extractField(json, "key_findings"));
        analysis.setLimitationsJson(extractField(json, "limitations"));
        analysis.setTablesSummaryJson(extractField(json, "tables_summary"));
        analysis.setFiguresSummaryJson(extractField(json, "figures_summary"));
    }

    /** 从 LLM 输出中提取 JSON —— 去掉 markdown 代码块包裹 */
    private String extractJson(String llmOutput) {
        String s = llmOutput.trim();
        // 去掉 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        return s;
    }

    /** 从 JSON 字符串中提取指定字段的值（简单版，够用） */
    private String extractField(String json, String fieldName) {
        // 匹配 "field_name": "value" 或 "field_name": [...] 或 "field_name": {...}
        String searchKey = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;

        // 跳过冒号后的空白
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) return null;

        char firstChar = json.charAt(start);

        if (firstChar == '"') {
            // 字符串值
            int end = json.indexOf('"', start + 1);
            // 处理转义引号
            while (end > 0 && json.charAt(end - 1) == '\\') {
                end = json.indexOf('"', end + 1);
            }
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else if (firstChar == '[') {
            // 数组值：找到匹配的 ]
            int depth = 0;
            int end = start;
            for (; end < json.length(); end++) {
                if (json.charAt(end) == '[') depth++;
                if (json.charAt(end) == ']') depth--;
                if (depth == 0) break;
            }
            return json.substring(start, end + 1);
        } else if (firstChar == '{') {
            // 对象值：找到匹配的 }
            int depth = 0;
            int end = start;
            for (; end < json.length(); end++) {
                if (json.charAt(end) == '{') depth++;
                if (json.charAt(end) == '}') depth--;
                if (depth == 0) break;
            }
            return json.substring(start, end + 1);
        }

        return null;
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

**第二步：输出 JSON**
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
  "figures_summary": [{"caption": "图表标题"}]
}
```

**规则**：信息无法确定时用空数组 [] 或空字符串 "";不要编造内容；sections 按实际结构输出。
""";
}
