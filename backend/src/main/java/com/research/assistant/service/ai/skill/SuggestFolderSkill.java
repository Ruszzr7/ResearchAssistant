package com.research.assistant.service.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.entity.Folder;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.skill.io.SuggestFolderInput;
import com.research.assistant.service.cache.RecommendationCache;
import com.research.assistant.service.rag.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件夹推荐 Skill。
 */
@Component
public class SuggestFolderSkill implements Skill<SuggestFolderInput, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(SuggestFolderSkill.class);

    private final PaperMapper paperMapper;
    private final FolderMapper folderMapper;
    private final ResearchToolAgent researchToolAgent;
    private final LLMService llmService;
    private final RecommendationCache recommendationCache;
    private final ObjectMapper objectMapper;
    private final RagRetrievalService ragRetrievalService;

    public SuggestFolderSkill(PaperMapper paperMapper, FolderMapper folderMapper,
                              ResearchToolAgent researchToolAgent, LLMService llmService,
                              RecommendationCache recommendationCache, ObjectMapper objectMapper,
                              RagRetrievalService ragRetrievalService) {
        this.paperMapper = paperMapper;
        this.folderMapper = folderMapper;
        this.researchToolAgent = researchToolAgent;
        this.llmService = llmService;
        this.recommendationCache = recommendationCache;
        this.objectMapper = objectMapper;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public String name() {
        return Skills.SUGGEST_FOLDER;
    }

    @Override
    public String description() {
        return "为单篇论文或标题推荐所属文件夹。输入：{\"paperId\": Long}（已有论文）或 {\"title\": \"论文标题\"}（导入前）；输出：Map{recommended, reason, suggestNew, newName}。";
    }

    @Override
    public Class<SuggestFolderInput> inputType() {
        return SuggestFolderInput.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, SuggestFolderInput input) {
        String title;
        String abstractText;
        Long cacheKey;

        if (input.paperId() != null) {
            Paper paper = paperMapper.selectById(input.paperId());
            if (paper == null) return Map.of("recommended", null, "suggestNew", false);
            title = paper.getTitle();
            abstractText = paper.getAbstractText();
            cacheKey = input.paperId();

            Map<String, Object> cached = recommendationCache.get("folder", cacheKey);
            if (cached != null) {
                log.debug("文件夹推荐命中缓存 paperId={}", cacheKey);
                return cached;
            }
        } else {
            title = input.title() != null ? input.title() : "";
            abstractText = "";
            cacheKey = null;
        }

        Map<String, Object> result = doSuggestFolder(title, abstractText);
        if (cacheKey != null) {
            recommendationCache.put("folder", cacheKey, result);
        }
        return result;
    }

    private Map<String, Object> doSuggestFolder(String title, String abstractText) {
        List<Folder> folders = folderMapper.selectList(null);
        if (folders.isEmpty()) {
            return Map.of("recommended", null, "suggestNew", true, "newName", "新文件夹");
        }

        StringBuilder folderList = new StringBuilder();
        for (Folder f : folders) {
            folderList.append("- ").append(f.getName()).append(" (id=").append(f.getId()).append(")\n");
        }

        String relatedSnippets = ragRetrievalService.retrieveAndRerankAsContext(
                title + "\n" + (abstractText != null ? abstractText : ""), 8, 0.65);

        try {
            var result = researchToolAgent.suggestFolder(
                    title,
                    abstractText != null ? abstractText : "",
                    folderList.toString(),
                    relatedSnippets);
            if (result != null && result.content() != null) {
                var pojo = result.content();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("recommended", pojo.getFolderId());
                map.put("reason", pojo.getReason());
                map.put("suggestNew", pojo.isSuggestNew());
                map.put("newName", pojo.getNewName());
                return map;
            }
        } catch (Exception e) {
            log.warn("Agent 文件夹推荐失败，回退到字符串解析: {}", e.getMessage());
        }

        String prompt = "论文标题：" + title + "\n摘要：" + (abstractText != null ? abstractText : "")
                + relatedSnippets
                + "\n现有文件夹列表：\n" + folderList +
                "\n请为这篇论文推荐最合适的现有文件夹。返回 JSON: {\"folderId\": 数字 或 null, \"reason\": \"一句话理由\", \"suggestNew\": true/false, \"newName\": \"建议新文件夹名（若 suggestNew 为 true）\"}";
        String result = llmService.chat("你是一位学术文献管理助手。请为论文推荐最合适的文件夹。只返回JSON。", prompt);
        try {
            return objectMapper.readValue(JsonUtils.extractJson(result), Map.class);
        } catch (Exception e) {
            return Map.of("recommended", null, "suggestNew", false);
        }
    }
}
