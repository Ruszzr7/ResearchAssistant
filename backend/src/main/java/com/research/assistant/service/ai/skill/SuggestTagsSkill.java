package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.cache.RecommendationCache;
import com.research.assistant.service.rag.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 标签推荐 Skill。
 */
@Component
public class SuggestTagsSkill implements Skill<Long, List<String>> {

    private static final Logger log = LoggerFactory.getLogger(SuggestTagsSkill.class);

    private final PaperMapper paperMapper;
    private final ResearchToolAgent researchToolAgent;
    private final LLMService llmService;
    private final RecommendationCache recommendationCache;
    private final RagRetrievalService ragRetrievalService;

    public SuggestTagsSkill(PaperMapper paperMapper, @Lazy ResearchToolAgent researchToolAgent,
                            LLMService llmService, RecommendationCache recommendationCache,
                            RagRetrievalService ragRetrievalService) {
        this.paperMapper = paperMapper;
        this.researchToolAgent = researchToolAgent;
        this.llmService = llmService;
        this.recommendationCache = recommendationCache;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public String name() {
        return Skills.SUGGEST_TAGS;
    }

    @Override
    public String description() {
        return "为单篇论文推荐标签。输入：{\"paperId\": Long}；输出：List<String>。";
    }

    @Override
    public Class<Long> inputType() {
        return Long.class;
    }

    @Override
    public List<String> execute(SkillContext ctx, Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) return Collections.emptyList();

        List<String> cached = recommendationCache.get("tag", paperId);
        if (cached != null) {
            log.debug("标签推荐命中缓存 paperId={}", paperId);
            return cached;
        }

        String abstractText = paper.getAbstractText() != null ? paper.getAbstractText() : "";
        String relatedSnippets = ragRetrievalService.retrieveAndRerankAsContext(
                paper.getTitle() + "\n" + abstractText, 8, 0.65);

        List<String> tags;
        try {
            var result = researchToolAgent.suggestTags(
                    paper.getTitle(), abstractText, relatedSnippets);
            if (result != null && result.content() != null && result.content().getTags() != null) {
                tags = result.content().getTags().stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            } else {
                tags = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("Agent 标签建议失败，回退到字符串解析: {}", e.getMessage());
            String prompt = "论文标题：" + paper.getTitle() + "\n摘要：" + abstractText
                    + relatedSnippets
                    + "\n\n请为这篇论文建议 3-5 个标签（技术关键词），用逗号分隔，只返回标签列表。";
            String result = llmService.chat("你是一位学术文献分类专家。为论文建议精准的分类标签。", prompt);
            tags = Arrays.stream(result.split("[，,]+")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }

        recommendationCache.put("tag", paperId, tags);
        return tags;
    }
}
