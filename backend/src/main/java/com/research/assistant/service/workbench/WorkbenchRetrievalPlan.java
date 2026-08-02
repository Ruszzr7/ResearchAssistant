package com.research.assistant.service.workbench;

import java.util.List;

/** Small deterministic query plan; it changes retrieval strategy, never paper facts. */
public record WorkbenchRetrievalPlan(QueryType queryType,
                                     String originalQuery,
                                     List<String> terms,
                                     List<String> phrases,
                                     boolean formulaOrLocation,
                                     boolean referentialFollowUp,
                                     boolean broad,
                                     int neighbourRadius) {
    public WorkbenchRetrievalPlan {
        queryType = queryType == null ? QueryType.EXPLANATION : queryType;
        originalQuery = originalQuery == null ? "" : originalQuery.trim();
        terms = terms == null ? List.of() : terms.stream().filter(value -> !value.isBlank()).distinct().toList();
        phrases = phrases == null ? List.of() : phrases.stream().filter(value -> !value.isBlank()).distinct().toList();
        neighbourRadius = Math.max(0, Math.min(3, neighbourRadius));
    }

    public enum QueryType {
        LOCATION,
        DEFINITION,
        SUMMARY,
        COMPARISON,
        EXPLANATION
    }
}
