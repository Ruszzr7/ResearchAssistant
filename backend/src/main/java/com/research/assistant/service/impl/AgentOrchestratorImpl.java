package com.research.assistant.service.impl;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.service.AgentOrchestrator;
import com.research.assistant.service.ai.skill.*;
import com.research.assistant.service.ai.skill.io.AnalyzeGapsInput;
import com.research.assistant.service.ai.skill.io.ChatInput;
import com.research.assistant.service.ai.skill.io.ComparePapersInput;
import com.research.assistant.service.ai.skill.io.SuggestFolderInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 编排器实现。
 * <p>
 * 处理状态机：PENDING → PROCESSING → COMPLETED / FAILED。
 * <p>
 * 当前实现已把具体 AI 能力拆分为独立 {@link Skill}，本类仅作为统一入口做参数封装与事务边界。
 */
@Service
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final AnalyzePaperSkill analyzePaperSkill;
    private final ComparePapersSkill comparePapersSkill;
    private final AnalyzeGapsSkill analyzeGapsSkill;
    private final VerifyGapsSkill verifyGapsSkill;
    private final SuggestTagsSkill suggestTagsSkill;
    private final SuggestFolderSkill suggestFolderSkill;
    private final SuggestReadingStatusSkill suggestReadingStatusSkill;
    private final ChatSkill chatSkill;
    private final SkillRegistry skillRegistry;

    public AgentOrchestratorImpl(AnalyzePaperSkill analyzePaperSkill,
                                  ComparePapersSkill comparePapersSkill,
                                  AnalyzeGapsSkill analyzeGapsSkill,
                                  VerifyGapsSkill verifyGapsSkill,
                                  SuggestTagsSkill suggestTagsSkill,
                                  SuggestFolderSkill suggestFolderSkill,
                                  SuggestReadingStatusSkill suggestReadingStatusSkill,
                                  ChatSkill chatSkill,
                                  SkillRegistry skillRegistry) {
        this.analyzePaperSkill = analyzePaperSkill;
        this.comparePapersSkill = comparePapersSkill;
        this.analyzeGapsSkill = analyzeGapsSkill;
        this.verifyGapsSkill = verifyGapsSkill;
        this.suggestTagsSkill = suggestTagsSkill;
        this.suggestFolderSkill = suggestFolderSkill;
        this.suggestReadingStatusSkill = suggestReadingStatusSkill;
        this.chatSkill = chatSkill;
        this.skillRegistry = skillRegistry;
    }

    @Override
    @Transactional
    public PaperAnalysis processPaper(Long paperId) {
        return execute(analyzePaperSkill, paperId, "process-" + paperId, "论文处理失败");
    }

    @Override
    @Transactional
    public String comparePapers(List<Long> paperIds, String customDimensions) {
        return execute(comparePapersSkill,
                new ComparePapersInput(paperIds, customDimensions),
                "compare-" + paperIds,
                "论文对比失败");
    }

    @Override
    public String analyzeGapsByPaperIds(List<Long> paperIds) {
        return execute(analyzeGapsSkill,
                new AnalyzeGapsInput(paperIds, null),
                "gap-" + paperIds,
                "Gap 分析失败");
    }

    @Override
    public String analyzeGaps(Long folderId) {
        return execute(analyzeGapsSkill,
                new AnalyzeGapsInput(null, folderId),
                "gap-folder-" + folderId,
                "Gap 分析失败");
    }

    @Override
    public List<Map<String, Object>> verifyGaps(String gapReport) {
        return execute(verifyGapsSkill, gapReport, "verify-gaps", "Gap 验证失败");
    }

    @Override
    public String chatAbout(String context, String question) {
        return executeQuietly(chatSkill,
                new ChatInput(null, context, question),
                "chat",
                "对话失败",
                "");
    }

    @Override
    public String chatAbout(String conversationId, String context, String question) {
        return executeQuietly(chatSkill,
                new ChatInput(conversationId, context, question),
                "chat-" + conversationId,
                "对话失败",
                "");
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

    @Override
    public Map<String, Object> suggestReadingStatus(Long paperId) {
        return executeQuietly(suggestReadingStatusSkill, paperId, "status-" + paperId,
                "阅读状态推荐失败",
                Map.of("status", com.research.assistant.constant.ReadingStatus.UNREAD,
                        "reason", "推荐失败，默认未读"));
    }

    /**
     * 执行会抛出的 Skill，非运行时异常包装为 RuntimeException。
     */
    private <I, O> O execute(Skill<I, O> skill, I input, String contextKey, String errorMessage) {
        try {
            return skill.execute(new SkillContext(contextKey), input);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(errorMessage + ": " + e.getMessage(), e);
        }
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
