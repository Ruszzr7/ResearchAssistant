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
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        query = query.trim();
        contentTypes = contentTypes == null ? Set.of() : Set.copyOf(contentTypes);
        if (pageStart != null && pageStart < 1 || pageEnd != null && pageEnd < 1) {
            throw new IllegalArgumentException("page filters must be 1-based");
        }
        if (pageStart != null && pageEnd != null && pageStart > pageEnd) {
            throw new IllegalArgumentException("pageStart cannot exceed pageEnd");
        }
        maxResults = Math.max(1, Math.min(50, maxResults));
    }
}
