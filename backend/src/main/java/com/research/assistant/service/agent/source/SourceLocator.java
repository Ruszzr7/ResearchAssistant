package com.research.assistant.service.agent.source;

import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.util.List;

public record SourceLocator(
        String locatorId,
        String sourceObjectId,
        int pageNumber,
        String coordinateSpace,
        List<NormalizedBoundingBox> rects,
        String targetText,
        EvidenceLocator.Precision precision
) {
    public SourceLocator {
        if (locatorId == null || locatorId.isBlank()) throw new IllegalArgumentException("locatorId is required");
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        if (pageNumber <= 0) throw new IllegalArgumentException("pageNumber must be 1-based");
        coordinateSpace = coordinateSpace == null ? "PDF_NORMALIZED" : coordinateSpace;
        rects = rects == null ? List.of() : List.copyOf(rects);
        if (rects.isEmpty()) throw new IllegalArgumentException("at least one source rect is required");
        targetText = targetText == null ? "" : targetText;
        precision = precision == null ? EvidenceLocator.Precision.BLOCK : precision;
    }
}
