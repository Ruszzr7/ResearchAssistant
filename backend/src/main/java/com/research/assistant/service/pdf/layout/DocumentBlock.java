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
                            double confidence) {

    public DocumentBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text;
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
