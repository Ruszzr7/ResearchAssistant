package com.research.assistant.service.agent.source;

import java.util.List;

public record CitationRequest(
        int answerStart,
        int answerEnd,
        String sourceObjectId,
        String quote,
        List<String> locatorIds
) {
    public CitationRequest {
        if (answerStart < 0 || answerEnd <= answerStart) throw new IllegalArgumentException("invalid answer span");
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        quote = quote == null ? "" : quote.trim();
        locatorIds = locatorIds == null ? List.of() : List.copyOf(locatorIds);
    }
}
