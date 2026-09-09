package com.research.assistant.service.agent.source;

import java.util.Set;

public record PaperSearchRequest(
        String query,
        Set<SourceContentType> contentTypes,
        Integer pageStart,
        Integer pageEnd,
        int maxResults
) {
    public PaperSearchRequest {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query 不能为空");
        query = query.trim();
        contentTypes = contentTypes == null ? Set.of() : Set.copyOf(contentTypes);
        if (pageStart != null && pageStart < 1 || pageEnd != null && pageEnd < 1) {
            throw new IllegalArgumentException("页码筛选必须从第 1 页开始");
        }
        if (pageStart != null && pageEnd != null && pageStart > pageEnd) {
            throw new IllegalArgumentException("起始页不能大于结束页");
        }
        maxResults = Math.max(1, Math.min(50, maxResults));
    }
}
