package com.research.assistant.service.agent.action;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.EvidenceLocator;

import java.util.List;

public record ActionTarget(long paperId, String documentHash, String sourceObjectId, int pageNumber,
                           List<String> locatorIds, List<NormalizedBoundingBox> rects,
                           String targetText, EvidenceLocator.Precision precision) {
    /** Keep the compact constructor used by older callers while exposing the
     * text/precision metadata needed to refine an Agent action with PDFium. */
    public ActionTarget(long paperId, String documentHash, String sourceObjectId, int pageNumber,
                        List<String> locatorIds, List<NormalizedBoundingBox> rects) {
        this(paperId, documentHash, sourceObjectId, pageNumber, locatorIds, rects, "",
                EvidenceLocator.Precision.BLOCK);
    }

    public ActionTarget {
        if (paperId <= 0 || pageNumber <= 0) throw new IllegalArgumentException("invalid action target");
        if (documentHash == null || documentHash.isBlank()) throw new IllegalArgumentException("documentHash is required");
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        locatorIds = locatorIds == null ? List.of() : List.copyOf(locatorIds);
        rects = rects == null ? List.of() : List.copyOf(rects);
        if (locatorIds.isEmpty() || rects.isEmpty()) throw new IllegalArgumentException("action target requires trusted locators");
        targetText = targetText == null ? "" : targetText;
        precision = precision == null ? EvidenceLocator.Precision.BLOCK : precision;
    }
}
