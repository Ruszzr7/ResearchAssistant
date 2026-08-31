package com.research.assistant.service.agent.source;

import java.util.List;

public record RetrievalHit(String sourceObjectId, double score, List<String> retrievalRoutes) {
    public RetrievalHit {
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        score = Math.max(0, Math.min(1, score));
        retrievalRoutes = retrievalRoutes == null ? List.of() : retrievalRoutes.stream().distinct().toList();
    }
}
