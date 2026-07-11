package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.cache.RecommendationCache;
import com.research.assistant.service.rag.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.Map;

/**
 * 阅读状态推荐 Skill。
 */
@Component
public class SuggestReadingStatusSkill implements Skill<Long, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(SuggestReadingStatusSkill.class);

    private static final Map<String, Object> DEFAULT_READING_STATUS = Map.of(
            "status", com.research.assistant.constant.ReadingStatus.UNREAD,
            "reason", "推荐失败，默认未读");

    private final PaperMapper paperMapper;
    private final ResearchToolAgent researchToolAgent;
    private final RecommendationCache recommendationCache;
    private final RagRetrievalService ragRetrievalService;

    public SuggestReadingStatusSkill(PaperMapper paperMapper,
                                     @Lazy ResearchToolAgent researchToolAgent,
                                     RecommendationCache recommendationCache,
                                     RagRetrievalService ragRetrievalService) {
        this.paperMapper = paperMapper;
        this.researchToolAgent = researchToolAgent;
        this.recommendationCache = recommendationCache;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public String name() {
        return Skills.SUGGEST_READING_STATUS;
    }

    @Override
    public String description() {
        return "推荐单篇论文的阅读状态（UNREAD/READING/READ）。输入：{\"paperId\": Long}；输出：Map{status, reason}。";
    }

    @Override
    public Class<Long> inputType() {
        return Long.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            return Map.of("status", com.research.assistant.constant.ReadingStatus.UNREAD,
                    "reason", "论文不存在，默认未读");
        }

        Map<String, Object> cached = recommendationCache.get("status", paperId);
        if (cached != null) {
            log.debug("阅读状态推荐命中缓存 paperId={}", paperId);
            return cached;
        }

        String abstractText = paper.getAbstractText() != null ? paper.getAbstractText() : "";
        String relatedSnippets = ragRetrievalService.retrieveAndRerankAsContext(
                paper.getTitle() + "\n" + abstractText, 8, 0.65);

        Map<String, Object> result;
        try {
            var agentResult = researchToolAgent.suggestReadingStatus(
                    paper.getTitle(), abstractText, relatedSnippets);
            if (agentResult != null && agentResult.content() != null) {
                var pojo = agentResult.content();
                result = Map.of(
                        "status", normalizeReadingStatus(pojo.getStatus()),
                        "reason", pojo.getReason() != null ? pojo.getReason() : "");
            } else {
                result = DEFAULT_READING_STATUS;
            }
        } catch (Exception e) {
            log.warn("Agent 阅读状态推荐失败，返回默认 UNREAD: {}", e.getMessage());
            result = DEFAULT_READING_STATUS;
        }

        recommendationCache.put("status", paperId, result);
        return result;
    }

    private String normalizeReadingStatus(String raw) {
        if (raw == null) return com.research.assistant.constant.ReadingStatus.UNREAD;
        return switch (raw.toUpperCase()) {
            case "READING" -> com.research.assistant.constant.ReadingStatus.READING;
            case "READ" -> com.research.assistant.constant.ReadingStatus.READ;
            default -> com.research.assistant.constant.ReadingStatus.UNREAD;
        };
    }
}
