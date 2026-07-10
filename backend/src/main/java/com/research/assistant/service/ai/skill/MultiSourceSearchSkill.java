package com.research.assistant.service.ai.skill;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.source.LiteratureCandidate;
import com.research.assistant.service.source.LiteratureSearchService;
import com.research.assistant.service.ai.skill.io.MultiSourceSearchInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 多源检索与去重 Skill —— 同时检索 arXiv 与本地文库，按标识符去重，生成候选列表。
 */
@Component
public class MultiSourceSearchSkill implements Skill<MultiSourceSearchInput, List<Map<String, Object>>> {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceSearchSkill.class);

    private final LiteratureSearchService literatureSearchService;
    private final PaperMapper paperMapper;

    public MultiSourceSearchSkill(LiteratureSearchService literatureSearchService,
                                  PaperMapper paperMapper) {
        this.literatureSearchService = literatureSearchService;
        this.paperMapper = paperMapper;
    }

    @Override
    public String name() {
        return Skills.MULTI_SOURCE_SEARCH;
    }

    @Override
    public String description() {
        return "基于检索要素在多个外部学术来源（arXiv/Semantic Scholar/Crossref）与本地文库中检索并去重。" +
                "输入：{elements: Map}；输出：候选论文列表。";
    }

    @Override
    public Class<MultiSourceSearchInput> inputType() {
        return MultiSourceSearchInput.class;
    }

    @Override
    public List<Map<String, Object>> execute(SkillContext ctx, MultiSourceSearchInput input) {
        ctx.stage("正在多源检索并去重…");
        Map<String, Object> elements = input.elements();
        if (elements == null) {
            elements = Map.of();
        }

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) elements.getOrDefault("keywords_en", Collections.emptyList());

        // 外部多源并行检索
        List<LiteratureCandidate> external = literatureSearchService.search(keywords, 8);
        List<Map<String, Object>> all = new ArrayList<>(
                literatureSearchService.toResultMaps(external, keywords));

        // 本地文库检索
        try {
            List<Paper> local = searchLocalPapers(keywords);
            for (Paper p : local) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("title", p.getTitle());
                map.put("authors", p.getAuthors());
                map.put("year", p.getYear());
                map.put("arxivId", p.getArxivId());
                map.put("doi", p.getDoi());
                map.put("sourceUrl", p.getSourceUrl());
                map.put("summary", p.getAbstractText());
                map.put("source", p.getSource());
                map.put("local", true);
                map.put("paperId", p.getId());
                map.put("recommendReason", "已存在于本地文库");
                all.add(map);
            }
        } catch (Exception e) {
            log.warn("本地文库检索失败: {}", e.getMessage());
        }

        return deduplicate(all);
    }

    private List<Paper> searchLocalPapers(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<Paper> wrapper = new QueryWrapper<>();
        boolean first = true;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String pattern = "%" + kw.trim() + "%";
            if (first) {
                wrapper.like("title", pattern).or().like("keywords", pattern);
                first = false;
            } else {
                wrapper.or().like("title", pattern).or().like("keywords", pattern);
            }
        }
        if (first) {
            return Collections.emptyList();
        }
        return paperMapper.selectList(wrapper);
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> papers) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> p : papers) {
            String key = dedupeKey(p);
            if (key == null || key.isBlank()) {
                key = "title:" + String.valueOf(p.get("title")).toLowerCase(Locale.ROOT);
            }
            if (!seen.containsKey(key)) {
                seen.put(key, p);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private String dedupeKey(Map<String, Object> p) {
        Object doi = p.get("doi");
        if (doi != null && !String.valueOf(doi).isBlank()) {
            return "doi:" + String.valueOf(doi).toLowerCase(Locale.ROOT);
        }
        Object arxivId = p.get("arxivId");
        if (arxivId != null && !String.valueOf(arxivId).isBlank()) {
            return "arxiv:" + String.valueOf(arxivId).toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
