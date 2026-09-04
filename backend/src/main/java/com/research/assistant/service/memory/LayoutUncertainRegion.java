package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.util.List;

/** A small, page-addressable layout area that merits visual verification. */
public record LayoutUncertainRegion(String regionId,
                                    String issueType,
                                    List<String> blockIds,
                                    List<PageArea> pageAreas,
                                    String rawText) {

    public LayoutUncertainRegion {
        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is required");
        issueType = issueType == null || issueType.isBlank() ? "LAYOUT_UNCERTAIN" : issueType;
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        pageAreas = pageAreas == null ? List.of() : List.copyOf(pageAreas);
        rawText = rawText == null ? "" : rawText.strip();
        if (blockIds.isEmpty() || pageAreas.isEmpty()) {
            throw new IllegalArgumentException("uncertain region requires blocks and page areas");
        }
    }

    public record PageArea(int page, List<NormalizedBoundingBox> boxes) {
        public PageArea {
            if (page <= 0) throw new IllegalArgumentException("page must be positive");
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
            if (boxes.isEmpty()) throw new IllegalArgumentException("page area requires boxes");
        }
    }
}
