package com.research.assistant.service.memory;

import java.util.List;

/** Minimal immutable provenance retained after a grounded workbench answer. */
public record PaperMemoryEvidenceRef(String evidenceId,
                                     String blockId,
                                     int page,
                                     List<String> sectionPath,
                                     String documentHash,
                                     String parserVersion) {
    public PaperMemoryEvidenceRef {
        evidenceId = safe(evidenceId);
        blockId = safe(blockId);
        page = Math.max(1, page);
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        documentHash = safe(documentHash);
        parserVersion = safe(parserVersion);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
