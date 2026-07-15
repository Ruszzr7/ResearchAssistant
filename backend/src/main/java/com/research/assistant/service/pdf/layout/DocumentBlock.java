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
                            DocumentBlockContentMode contentMode) {

    public DocumentBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text;
        confidence = Math.max(0, Math.min(1, confidence));
        contentMode = contentMode == null ? inferContentMode(role, latex, tableText) : contentMode;
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
                confidence, null);
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
