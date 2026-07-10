package com.research.assistant.service.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 学术文献来源抽象接口。
 * <p>
 * 每个实现负责把一个特定学术数据源（arXiv、Semantic Scholar、Crossref 等）
 * 的原始返回归一化为 {@link LiteratureCandidate}。
 */
public interface LiteratureSource {

    /**
     * 来源唯一名称，如 "arXiv"、"Semantic Scholar"、"Crossref"。
     */
    String sourceName();

    /**
     * 是否支持关键词检索。
     */
    default boolean supportsSearch() {
        return true;
    }

    /**
     * 按关键词搜索。
     *
     * @param query      搜索关键词（建议英文术语）
     * @param maxResults 每源最大返回数
     * @return 候选列表；失败或无可用的来源应返回空列表而非抛异常
     */
    List<LiteratureCandidate> search(String query, int maxResults);

    /**
     * 对多个关键词顺序搜索并合并结果。
     * <p>
     * 默认实现按顺序搜索，子类可覆写以做并发或限速控制。
     *
     * @param keywords   关键词列表
     * @param maxResults 每关键词每源最大返回数
     * @return 合并后的候选列表
     */
    default List<LiteratureCandidate> searchKeywords(List<String> keywords, int maxResults) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        List<LiteratureCandidate> all = new ArrayList<>();
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            try {
                all.addAll(search(kw.trim(), maxResults));
            } catch (Exception e) {
                // 单个关键词失败不影响其他关键词
            }
        }
        return all;
    }
}
