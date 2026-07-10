package com.research.assistant.service.writing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.dto.*;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.rag.RagRetrievalService;
import com.research.assistant.service.rag.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 写作辅助 AI 服务：大纲生成、Related Work 生成、引用推荐与冲突检查。
 */
@Service
public class WritingAssistantService {

    private static final Logger log = LoggerFactory.getLogger(WritingAssistantService.class);

    private final LLMService llmService;
    private final SettingsService settingsService;
    private final PaperMapper paperMapper;
    private final PaperAnalysisMapper paperAnalysisMapper;
    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;

    public WritingAssistantService(LLMService llmService,
                                   SettingsService settingsService,
                                   PaperMapper paperMapper,
                                   PaperAnalysisMapper paperAnalysisMapper,
                                   RagRetrievalService ragRetrievalService,
                                   ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.settingsService = settingsService;
        this.paperMapper = paperMapper;
        this.paperAnalysisMapper = paperAnalysisMapper;
        this.ragRetrievalService = ragRetrievalService;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据选题生成学术论文大纲。
     */
    public OutlineDto generateOutline(String topic, String style, String language) {
        String researchTopic = Optional.ofNullable(settingsService.getValue("research_topic")).orElse("");
        String system = """
                你是一名学术写作助手。请根据用户给定的研究选题，生成一篇学术论文的结构化大纲。
                要求：
                - 输出必须是合法 JSON，不要包含 markdown 代码块标记或其他说明文字。
                - JSON 格式：{"sections":[{"level":1,"title":"...","children":[...]}]}，level 取值 1-4。
                - 大纲应包含摘要、引言、相关工作、方法、实验、结论等必要章节，标题使用用户指定的语言。
                """;
        String user = buildUserPrompt(topic, researchTopic, style, language);
        String raw = llmService.chat(system, user);
        return parseOutline(raw);
    }

    /**
     * 基于已选论文生成 Related Work 段落。
     */
    public RelatedWorkDto generateRelatedWork(List<Long> paperIds, String topic, String style) {
        if (paperIds == null || paperIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一篇论文");
        }
        String researchTopic = Optional.ofNullable(settingsService.getValue("research_topic")).orElse("");
        Map<Long, PaperAnalysis> analyses = fetchAnalyses(paperIds);
        if (analyses.isEmpty()) {
            throw new IllegalArgumentException("所选论文暂无分析数据，请先执行论文精读分析");
        }

        String system = """
                你是一名学术写作助手。请根据提供的论文分析信息，为用户的研究选题撰写一段连贯的 Related Work（相关工作）。
                要求：
                - 按主题、方法或时间线组织，不要简单罗列论文。
                - 在合适位置使用 [paperId] 占位符标记引用，例如 [1]、[2]。
                - 输出必须是合法 JSON，不要包含 markdown 代码块标记或其他说明文字。
                - JSON 格式：{"content":"段落内容...","citations":[{"paperId":1,"placeholder":"[1]","sentence":"引用该论文的句子"}]}。
                """;
        String user = buildRelatedWorkUserPrompt(analyses, topic, researchTopic, style);
        String raw = llmService.chat(system, user);
        return parseRelatedWork(raw);
    }

    /**
     * 对用户段落做引用推荐与冲突检查。
     */
    public CitationCheckDto checkCitations(String paragraph, List<Long> paperIds) {
        if (paragraph == null || paragraph.isBlank()) {
            throw new IllegalArgumentException("待检查段落不能为空");
        }
        List<ScoredChunk> chunks = ragRetrievalService.retrieveAndRerank(paragraph, 10, 0.6);
        Map<Long, PaperAnalysis> analyses = (paperIds != null && !paperIds.isEmpty()) ? fetchAnalyses(paperIds) : Map.of();

        String system = """
                你是一名学术审稿助手。请根据用户提供的段落和参考论文片段，完成以下任务：
                1. 推荐哪些论文应该被引用，并说明应放在段落的哪个位置（如"第一句后"、"方法描述段"等）。
                2. 判断该段落是否与已有文献存在观点重复（DUPLICATE）或方法冲突（CONFLICT）。
                要求：
                - 输出必须是合法 JSON，不要包含 markdown 代码块标记或其他说明文字。
                - JSON 格式：{"suggestions":[{"paperId":1,"reason":"...","position":"..."}],"conflicts":[{"paperId":2,"type":"DUPLICATE|CONFLICT","reason":"..."}]}。
                - 如果没有任何建议或冲突，返回对应空数组。
                """;
        String user = buildCitationCheckUserPrompt(paragraph, chunks, analyses);
        String raw = llmService.chat(system, user);
        return parseCitationCheck(raw, analyses);
    }

    // ========== Prompt 构建 ==========

    private String buildUserPrompt(String topic, String researchTopic, String style, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append("研究选题：").append(topic).append("\n");
        if (!researchTopic.isBlank()) sb.append("用户当前研究主题：").append(researchTopic).append("\n");
        if (style != null && !style.isBlank()) sb.append("风格要求：").append(style).append("\n");
        if (language != null && !language.isBlank()) sb.append("输出语言：").append(language).append("\n");
        return sb.toString();
    }

    private String buildRelatedWorkUserPrompt(Map<Long, PaperAnalysis> analyses,
                                              String topic, String researchTopic, String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户研究选题：").append(topic).append("\n");
        if (!researchTopic.isBlank()) sb.append("用户当前研究主题：").append(researchTopic).append("\n");
        if (style != null && !style.isBlank()) sb.append("写作风格：").append(style).append("\n");
        sb.append("\n以下是需要综述的论文分析信息：\n");
        analyses.forEach((paperId, analysis) -> {
            Paper paper = paperMapper.selectById(paperId);
            sb.append("\n--- 论文 ID ").append(paperId).append(" ---\n");
            sb.append("标题：").append(paper != null && paper.getTitle() != null ? paper.getTitle() : "未知").append("\n");
            sb.append("核心贡献：").append(nullToEmpty(analysis.getCoreContribution())).append("\n");
            sb.append("方法概述：").append(nullToEmpty(analysis.getMethodSummary())).append("\n");
            sb.append("主要发现：").append(nullToEmpty(analysis.getKeyFindingsJson())).append("\n");
            sb.append("局限性：").append(nullToEmpty(analysis.getLimitationsJson())).append("\n");
        });
        return sb.toString();
    }

    private String buildCitationCheckUserPrompt(String paragraph,
                                                List<ScoredChunk> chunks,
                                                Map<Long, PaperAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("待检查段落：\n").append(paragraph).append("\n\n");
        if (!chunks.isEmpty()) {
            sb.append("通过向量检索得到的相关论文片段：\n");
            for (int i = 0; i < chunks.size(); i++) {
                ScoredChunk c = chunks.get(i);
                sb.append(i + 1).append(". [paperId=").append(c.paperId()).append("] ")
                        .append(c.content()).append("\n");
            }
            sb.append("\n");
        }
        if (!analyses.isEmpty()) {
            sb.append("已选论文的分析摘要：\n");
            analyses.forEach((paperId, analysis) -> {
                Paper paper = paperMapper.selectById(paperId);
                sb.append("- [").append(paperId).append("] ")
                        .append(paper != null && paper.getTitle() != null ? paper.getTitle() : "未知")
                        .append("：")
                        .append(nullToEmpty(analysis.getCoreContribution())).append("\n");
            });
        }
        return sb.toString();
    }

    // ========== 数据获取 ==========

    private Map<Long, PaperAnalysis> fetchAnalyses(List<Long> paperIds) {
        return paperAnalysisMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                                .in(PaperAnalysis::getPaperId, paperIds))
                .stream()
                .collect(Collectors.toMap(PaperAnalysis::getPaperId, a -> a, (a, b) -> a));
    }

    // ========== JSON 解析 ==========

    private OutlineDto parseOutline(String raw) {
        String json = JsonUtils.extractJson(raw);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("LLM 返回的大纲为空");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object sections = map.get("sections");
            OutlineDto dto = new OutlineDto();
            if (sections instanceof List) {
                dto.setSections(parseOutlineSections((List<?>) sections));
            }
            return dto;
        } catch (Exception e) {
            log.warn("大纲 JSON 解析失败: {}", e.getMessage());
            throw new IllegalStateException("无法解析 LLM 返回的大纲，请重试: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<OutlineDto.OutlineSection> parseOutlineSections(List<?> list) {
        List<OutlineDto.OutlineSection> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            OutlineDto.OutlineSection section = new OutlineDto.OutlineSection();
            Object level = map.get("level");
            section.setLevel(level instanceof Number ? ((Number) level).intValue() : 1);
            section.setTitle(String.valueOf(map.getOrDefault("title", "")));
            Object children = map.get("children");
            if (children instanceof List) {
                section.setChildren(parseOutlineSections((List<?>) children));
            }
            result.add(section);
        }
        return result;
    }

    private RelatedWorkDto parseRelatedWork(String raw) {
        String json = JsonUtils.extractJson(raw);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("LLM 返回的 Related Work 为空");
        }
        try {
            return objectMapper.readValue(json, RelatedWorkDto.class);
        } catch (Exception e) {
            log.warn("Related Work JSON 解析失败: {}", e.getMessage());
            RelatedWorkDto fallback = new RelatedWorkDto();
            fallback.setContent(raw);
            return fallback;
        }
    }

    private CitationCheckDto parseCitationCheck(String raw, Map<Long, PaperAnalysis> analyses) {
        String json = JsonUtils.extractJson(raw);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("LLM 返回的引用检查结果为空");
        }
        try {
            CitationCheckDto dto = objectMapper.readValue(json, CitationCheckDto.class);
            enrichTitles(dto.getSuggestions(), dto.getConflicts(), analyses);
            return dto;
        } catch (Exception e) {
            log.warn("引用检查 JSON 解析失败: {}", e.getMessage());
            throw new IllegalStateException("无法解析 LLM 返回的引用检查结果，请重试: " + e.getMessage());
        }
    }

    private void enrichTitles(List<CitationCheckDto.Suggestion> suggestions,
                              List<CitationCheckDto.Conflict> conflicts,
                              Map<Long, PaperAnalysis> analyses) {
        Set<Long> allIds = new HashSet<>();
        Optional.ofNullable(suggestions).orElse(List.of()).forEach(s -> allIds.add(s.getPaperId()));
        Optional.ofNullable(conflicts).orElse(List.of()).forEach(c -> allIds.add(c.getPaperId()));
        Map<Long, String> titleMap = allIds.stream()
                .map(paperMapper::selectById)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Paper::getId, p -> p.getTitle() != null ? p.getTitle() : "未知"));
        if (suggestions != null) {
            suggestions.forEach(s -> s.setPaperTitle(titleMap.getOrDefault(s.getPaperId(), "未知论文")));
        }
        if (conflicts != null) {
            conflicts.forEach(c -> c.setPaperTitle(titleMap.getOrDefault(c.getPaperId(), "未知论文")));
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
