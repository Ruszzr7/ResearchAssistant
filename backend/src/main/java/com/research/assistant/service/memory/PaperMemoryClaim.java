package com.research.assistant.service.memory;

import java.util.List;

/** A semantic assertion that remains grounded in versioned layout block IDs. */
public record PaperMemoryClaim(String category,
                               String statement,
                               List<String> evidenceBlockIds,
                               double confidence) {

    public PaperMemoryClaim {
        category = safe(category, "OTHER");
        statement = safe(statement, "");
        evidenceBlockIds = evidenceBlockIds == null ? List.of() : List.copyOf(evidenceBlockIds);
        confidence = Double.isFinite(confidence) ? Math.max(0, Math.min(1, confidence)) : 0;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
