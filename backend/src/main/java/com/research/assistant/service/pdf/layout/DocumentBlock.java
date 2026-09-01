package com.research.assistant.service.pdf.layout;

import java.util.List;

/**
 * One addressable text or visual block in a PDF layout artifact.
 */
public record DocumentBlock(String id,
                            int page,
                            NormalizedBoundingBox bbox,
                            DocumentBlockRole role,
                            int readingOrder,
                            List<String> sectionPath,
                            String text,
                            String latex,
                            String tableText,
                            double confidence,
                            DocumentBlockContentMode contentMode,
                            MathContentProfile mathProfile,
                            DocumentLayoutLane layoutLane) {

    public DocumentBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text;
        confidence = Math.max(0, Math.min(1, confidence));
        contentMode = contentMode == null ? inferContentMode(role, latex, tableText) : contentMode;
        mathProfile = mathProfile == null ? MathContentProfile.none("") : mathProfile;
        layoutLane = layoutLane == null ? DocumentLayoutLane.UNKNOWN : layoutLane;
    }

    /** Compatibility constructor for existing deterministic parsers and fixtures. */
    public DocumentBlock(String id,
                         int page,
                         NormalizedBoundingBox bbox,
                         DocumentBlockRole role,
                         int readingOrder,
                         List<String> sectionPath,
                         String text,
                         String latex,
                         String tableText,
                         double confidence) {
        this(id, page, bbox, role, readingOrder, sectionPath, text, latex, tableText,
                confidence, null, null, DocumentLayoutLane.UNKNOWN);
    }

    /** Compatibility constructor for callers that explicitly set the content mode. */
    public DocumentBlock(String id,
                         int page,
                         NormalizedBoundingBox bbox,
                         DocumentBlockRole role,
                         int readingOrder,
                         List<String> sectionPath,
                         String text,
                         String latex,
                         String tableText,
                         double confidence,
                         DocumentBlockContentMode contentMode) {
        this(id, page, bbox, role, readingOrder, sectionPath, text, latex, tableText,
                confidence, contentMode, null, DocumentLayoutLane.UNKNOWN);
    }

    /** Compatibility constructor for callers that explicitly set math metadata. */
    public DocumentBlock(String id,
                         int page,
                         NormalizedBoundingBox bbox,
                         DocumentBlockRole role,
                         int readingOrder,
                         List<String> sectionPath,
                         String text,
                         String latex,
                         String tableText,
                         double confidence,
                         DocumentBlockContentMode contentMode,
                         MathContentProfile mathProfile) {
        this(id, page, bbox, role, readingOrder, sectionPath, text, latex, tableText,
                confidence, contentMode, mathProfile, DocumentLayoutLane.UNKNOWN);
    }

    private static DocumentBlockContentMode inferContentMode(DocumentBlockRole role,
                                                             String latex,
                                                             String tableText) {
        if (role == DocumentBlockRole.FORMULA) {
            return latex == null || latex.isBlank()
                    ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.STRUCTURED;
        }
        if (role == DocumentBlockRole.TABLE) {
            return tableText == null || tableText.isBlank()
                    ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.STRUCTURED;
        }
        if (role == DocumentBlockRole.FIGURE) {
            return DocumentBlockContentMode.REGION;
        }
        return DocumentBlockContentMode.TEXT;
    }
}
