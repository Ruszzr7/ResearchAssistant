package com.research.assistant.service.agent.action;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.util.List;

public record ActionTarget(long paperId, String documentHash, String sourceObjectId, int pageNumber,
                           List<String> locatorIds, List<NormalizedBoundingBox> rects) {
    public ActionTarget {
        if (paperId <= 0 || pageNumber <= 0) throw new IllegalArgumentException("invalid action target");
        if (documentHash == null || documentHash.isBlank()) throw new IllegalArgumentException("documentHash is required");
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        locatorIds = locatorIds == null ? List.of() : List.copyOf(locatorIds);
        rects = rects == null ? List.of() : List.copyOf(rects);
        if (locatorIds.isEmpty() || rects.isEmpty()) throw new IllegalArgumentException("action target requires trusted locators");
    }
}
