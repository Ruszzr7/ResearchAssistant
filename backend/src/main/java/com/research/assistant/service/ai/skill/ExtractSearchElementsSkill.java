package com.research.assistant.service.ai.skill;

import com.research.assistant.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提炼检索要素 Skill —— 把自然语言查询转换为结构化检索要素。
 */
@Component
public class ExtractSearchElementsSkill implements Skill<String, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(ExtractSearchElementsSkill.class);

    private final SearchService searchService;

    public ExtractSearchElementsSkill(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return Skills.EXTRACT_SEARCH_ELEMENTS;
    }

    @Override
    public String description() {
        return "将自然语言查询提炼为结构化检索要素。输入：String 查询；输出：Map{domain, keywords_en, keywords_cn, time_range, paper_type, explanation}。";
    }

    @Override
    public Class<String> inputType() {
        return String.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, String query) {
        ctx.stage("正在提炼检索要素…");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索查询不能为空");
        }
        Map<String, Object> elements = searchService.extractSearchParams(query);
        log.debug("提炼检索要素完成: {}", elements.get("keywords_en"));
        return elements;
    }
}
