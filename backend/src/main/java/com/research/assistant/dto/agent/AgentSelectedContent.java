package com.research.assistant.dto.agent;

import java.util.List;

public record AgentSelectedContent(
        String selectionId,
        long paperId,
        String documentHash,
        int pageNumber,
        String contentType,
        String exactText,
        List<String> sourceObjectIds
) {
    public AgentSelectedContent {
        if (selectionId == null || selectionId.isBlank()) throw new IllegalArgumentException("selectionId is required");
        if (paperId <= 0 || pageNumber <= 0) throw new IllegalArgumentException("paperId and pageNumber must be positive");
        if (documentHash == null || documentHash.isBlank()) throw new IllegalArgumentException("documentHash is required");
        if (exactText == null || exactText.isBlank()) throw new IllegalArgumentException("exactText is required");
        contentType = contentType == null || contentType.isBlank() ? "TEXT" : contentType.trim().toUpperCase();
        sourceObjectIds = sourceObjectIds == null ? List.of() : List.copyOf(sourceObjectIds);
    }
}
