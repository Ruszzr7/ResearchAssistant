package com.research.assistant.service.impl;

import com.research.assistant.common.JsonUtils;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SearchService;
import com.research.assistant.service.source.LiteratureCandidate;
import com.research.assistant.service.source.LiteratureSearchService;
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
    private final LiteratureSearchService literatureSearchService;

    public SearchServiceImpl(LLMService llmService,
                             LiteratureSearchService literatureSearchService) {
        this.llmService = llmService;
        this.literatureSearchService = literatureSearchService;
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
        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) params.getOrDefault("keywords_en", Collections.emptyList());
        if (keywords.isEmpty()) {
            return List.of();
        }

        List<LiteratureCandidate> candidates = literatureSearchService.search(keywords, 15);
        return literatureSearchService.toResultMaps(candidates, keywords);
    }

    /** 解析 LLM 返回的 JSON（处理可能的 markdown 包裹） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAgentJson(String llmOutput) {
        String json = JsonUtils.extractJson(llmOutput);
        if (json == null) {
            json = "";
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
