package com.research.assistant.service.impl;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 智能检索服务实现。
 */
@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final LLMService llmService;
    private final ArxivFetcher arxivFetcher;
    private final PaperMapper paperMapper;

    public SearchServiceImpl(LLMService llmService, ArxivFetcher arxivFetcher, PaperMapper paperMapper) {
        this.llmService = llmService;
        this.arxivFetcher = arxivFetcher;
        this.paperMapper = paperMapper;
    }

    @Override
    public Map<String, Object> extractSearchParams(String userInput) {
        String prompt = "用户描述了一个研究方向，请提取检索要素，以 JSON 格式返回：\n\n"
                + "用户输入：" + userInput + "\n\n"
                + "返回格式：\n"
                + "{\n"
                + "  \"domain\": \"研究领域（如NLP/CV/通信/控制等）\",\n"
                + "  \"sub_direction\": \"子方向的一句话描述\",\n"
                + "  \"keywords_en\": [\"英文关键词1\", \"英文关键词2\", ...],\n"
                + "  \"keywords_cn\": [\"中文关键词1\", ...],\n"
                + "  \"time_range\": \"建议时间范围（近N年）\",\n"
                + "  \"paper_type\": [\"conference\", \"journal\", \"preprint\"],\n"
                + "  \"explanation\": \"一句话解释你的提炼逻辑\"\n"
                + "}\n\n"
                + "注意：keywords_en 是最关键的部分，必须使用标准学术术语，每个关键词单独列出。";

        String result = llmService.chat(
                "你是一位资深学术文献检索专家。请根据用户的自然语言描述，精确提取检索要素。只返回JSON，不要其他内容。",
                prompt);
        return parseAgentJson(result);
    }

    @Override
    public List<Map<String, Object>> executeSearch(Map<String, Object> params) {
        // 用英文关键词构建查询
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) params.getOrDefault("keywords_en", Collections.emptyList());
        String query = String.join(" AND ", keywords);

        try {
            List<Map<String, Object>> papers = arxivFetcher.search(query, 15);
            // 为每篇论文生成推荐理由
            for (Map<String, Object> paper : papers) {
                String reason = generateRecommendReason(paper, params);
                paper.put("recommendReason", reason);
            }
            return papers;
        } catch (Exception e) {
            throw new RuntimeException("检索执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> expandSearch(List<String> queries) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 扩展策略
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("cited_by", "检索引用这些论文的后续研究");
        strategy.put("related_articles", "检索 arXiv 上的相关工作");
        strategy.put("author_tracking", "追踪一作和通信作者的其他论文");
        strategy.put("depth", "1-2 层扩展");
        result.put("strategy", strategy);

        // 对每个查询词执行扩展检索
        List<Map<String, Object>> allResults = new ArrayList<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) continue;
            try {
                // 取前几个关键词作为扩展查询
                String[] words = query.split("\\s+");
                String q = String.join(" AND ", java.util.Arrays.copyOf(words, Math.min(5, words.length)));
                List<Map<String, Object>> related = arxivFetcher.search(q, 5);
                allResults.addAll(related);
            } catch (Exception e) {
                log.warn("扩展检索失败 query={}: {}", query, e.getMessage());
            }
        }

        // 去重（按 arxivId）
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> p : allResults) {
            String aid = (String) p.getOrDefault("arxivId", "");
            if (!aid.isEmpty() && !deduped.containsKey(aid)) {
                deduped.put(aid, p);
            }
        }
        result.put("results", new ArrayList<>(deduped.values()));
        result.put("total", deduped.size());
        return result;
    }

    /** 为检索结果生成推荐理由 */
    private String generateRecommendReason(Map<String, Object> paper, Map<String, Object> params) {
        String title = (String) paper.getOrDefault("title", "");
        String summary = (String) paper.getOrDefault("summary", "");
        if (title.isEmpty()) return "关键词匹配";

        String prompt = "论文标题：" + title + "\n"
                + "论文摘要：" + (summary.length() > 500 ? summary.substring(0, 500) + "…" : summary) + "\n"
                + "检索关键词：" + params.getOrDefault("keywords_en", "") + "\n\n"
                + "用一句话（20字以内）说明这篇论文为什么和检索目标相关。只返回这句话。";

        try {
            return llmService.chat("你是一位文献检索助手。简洁说明论文与检索目标的相关性。", prompt);
        } catch (Exception e) {
            return "关键词匹配";
        }
    }

    /** 解析 LLM 返回的 JSON（处理可能的 markdown 包裹） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAgentJson(String llmOutput) {
        String json = llmOutput.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) json = json.substring(start + 1, end).trim();
        }
        // 简化：返回可解析的结果
        Map<String, Object> result = new HashMap<>();
        result.put("raw_response", json);
        try {
            // 尝试用 Jackson 解析
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            // 解析失败，返回原始响应
            result.put("parse_error", true);
            result.put("keywords_en", Collections.singletonList(""));
            return result;
        }
    }
}
