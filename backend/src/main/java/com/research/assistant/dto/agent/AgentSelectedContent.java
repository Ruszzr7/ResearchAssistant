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
    public static final int MAX_TEXT_CHARACTERS = 4_000;
    public static final int MAX_FORMULA_CHARACTERS = 2_000;

    public AgentSelectedContent {
        if (selectionId == null || selectionId.isBlank()) throw new IllegalArgumentException("selectionId is required");
        if (paperId <= 0 || pageNumber <= 0) throw new IllegalArgumentException("paperId and pageNumber must be positive");
        if (documentHash == null || documentHash.isBlank()) throw new IllegalArgumentException("documentHash is required");
        if (exactText == null || exactText.isBlank()) throw new IllegalArgumentException("exactText is required");
        contentType = contentType == null || contentType.isBlank() ? "TEXT" : contentType.trim().toUpperCase();
        int limit = contentType.contains("FORMULA") ? MAX_FORMULA_CHARACTERS : MAX_TEXT_CHARACTERS;
        if (exactText.length() > limit) throw new IllegalArgumentException("选取内容过长");
        sourceObjectIds = sourceObjectIds == null ? List.of() : List.copyOf(sourceObjectIds);
    }
}
