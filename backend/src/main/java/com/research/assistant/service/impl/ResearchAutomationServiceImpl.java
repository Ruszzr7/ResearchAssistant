package com.research.assistant.service.impl;

import com.research.assistant.service.ResearchAutomationService;
import com.research.assistant.service.ai.skill.*;
import com.research.assistant.service.ai.skill.io.SuggestFolderInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 非对话式研究自动化实现。
 * <p>
 * 标签和文件夹推荐由独立 {@link Skill} 实现，本类负责参数封装和失败降级。
 */
@Service
public class ResearchAutomationServiceImpl implements ResearchAutomationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchAutomationServiceImpl.class);

    private final SuggestTagsSkill suggestTagsSkill;
    private final SuggestFolderSkill suggestFolderSkill;

    public ResearchAutomationServiceImpl(SuggestTagsSkill suggestTagsSkill,
                                         SuggestFolderSkill suggestFolderSkill) {
        this.suggestTagsSkill = suggestTagsSkill;
        this.suggestFolderSkill = suggestFolderSkill;
    }

    @Override
    public List<String> suggestTags(Long paperId) {
        return executeQuietly(suggestTagsSkill, paperId, "tags-" + paperId,
                "标签推荐失败", List.of());
    }

    @Override
    public Map<String, Object> suggestFolder(Long paperId) {
        return executeQuietly(suggestFolderSkill,
                new SuggestFolderInput(paperId, null),
                "folder-" + paperId,
                "文件夹推荐失败",
                folderSuggestionFallback());
    }

    @Override
    public Map<String, Object> suggestFolderByTitle(String title) {
        return suggestFolderByTitle(title, null);
    }

    @Override
    public Map<String, Object> suggestFolderByTitle(String title, String abstractText) {
        return suggestFolderByTitle(title, abstractText, null);
    }

    @Override
    public Map<String, Object> suggestFolderByTitle(String title, String abstractText, String keywords) {
        return executeQuietly(suggestFolderSkill,
                new SuggestFolderInput(null, title, abstractText, keywords),
                "folder-title",
                "文件夹推荐失败",
                folderSuggestionFallback());
    }

    private Map<String, Object> folderSuggestionFallback() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("recommended", null);
        fallback.put("reason", "AI 推荐暂不可用，请手动选择文件夹");
        fallback.put("suggestNew", false);
        fallback.put("newName", null);
        fallback.put("parentFolderId", null);
        return fallback;
    }

    /**
     * 执行可能失败的 Skill，失败时记录日志并返回兜底值。
     */
    private <I, O> O executeQuietly(Skill<I, O> skill, I input, String contextKey,
                                    String logMessage, O fallback) {
        try {
            return skill.execute(new SkillContext(contextKey), input);
        } catch (Exception e) {
            log.warn("{}: {}", logMessage, e.getMessage());
            return fallback;
        }
    }
}
