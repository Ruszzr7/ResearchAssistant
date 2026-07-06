package com.research.assistant.service;

import java.util.List;
import java.util.Map;

/**
 * 智能检索服务 —— 对话式文献检索与发现。
 * <p>
 * 对应 Spec 功能二的六步流程。
 */
public interface SearchService {

    /**
     * Step 2: Agent 提炼用户输入为检索要素。
     * 返回结构化分析结果：domain, keywords, timeRange, paperType
     */
    Map<String, Object> extractSearchParams(String userInput);

    /**
     * Step 3: 执行检索，返回精选 Top 10-15 篇论文。
     * 每篇附带推荐理由。
     */
    List<Map<String, Object>> executeSearch(Map<String, Object> params);

    /**
     * Step 5-6: 基于已选论文（标题/关键词）生成扩展策略并执行。
     */
    Map<String, Object> expandSearch(java.util.List<String> queries);
}
