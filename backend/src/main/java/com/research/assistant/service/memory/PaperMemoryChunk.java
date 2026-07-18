package com.research.assistant.service.memory;

import java.util.List;

/** One bounded, section-aware model input with stable layout-block provenance. */
public record PaperMemoryChunk(String id,
                               String sourceFingerprint,
                               int ordinal,
                               String sectionId,
                               List<String> headingPath,
                               int pageStart,
                               int pageEnd,
                               List<String> blockIds,
                               String text) {

    public PaperMemoryChunk {
        id = safe(id);
        sourceFingerprint = safe(sourceFingerprint);
        sectionId = safe(sectionId);
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        text = safe(text);
    }

    public int characterCount() {
        return text.length();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
