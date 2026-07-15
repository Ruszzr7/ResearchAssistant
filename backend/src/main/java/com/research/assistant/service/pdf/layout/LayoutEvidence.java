package com.research.assistant.service.pdf.layout;

import java.util.List;

/** One deterministic, page-addressable evidence item from a layout artifact. */
public record LayoutEvidence(String evidenceId,
                             Long paperId,
                             String blockId,
                             int page,
                             NormalizedBoundingBox bbox,
                             DocumentBlockRole role,
                             int readingOrder,
                             List<String> sectionPath,
                             String text,
                             double score,
                             boolean selected,
                             double confidence,
                             String documentHash,
                             String parserVersion,
                             DocumentBlockContentMode contentMode,
                             String structuredContent) {

    public LayoutEvidence {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text;
        score = Math.max(0, Math.min(1, score));
        confidence = Math.max(0, Math.min(1, confidence));
        contentMode = contentMode == null ? DocumentBlockContentMode.TEXT : contentMode;
        structuredContent = structuredContent == null ? "" : structuredContent;
    }

    /** Compatibility constructor for existing tests and deterministic callers. */
    public LayoutEvidence(String evidenceId,
                          Long paperId,
                          String blockId,
                          int page,
                          NormalizedBoundingBox bbox,
                          DocumentBlockRole role,
                          int readingOrder,
                          List<String> sectionPath,
                          String text,
                          double score,
                          boolean selected,
                          double confidence,
                          String documentHash,
                          String parserVersion) {
        this(evidenceId, paperId, blockId, page, bbox, role, readingOrder,
                sectionPath, text, score, selected, confidence, documentHash,
                parserVersion, DocumentBlockContentMode.TEXT, "");
    }
}
