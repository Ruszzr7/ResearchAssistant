package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Stable, version-bound address of a source span in the rendered PDF. */
public record SourceAnchor(String anchorId,
                           int page,
                           Kind kind,
                           NormalizedBoundingBox bbox,
                           List<NormalizedBoundingBox> boxes,
                           String targetText,
                           String blockId,
                           double confidence) {

    public SourceAnchor {
        anchorId = anchorId == null ? "" : anchorId;
        kind = kind == null ? Kind.TEXT_RANGE : kind;
        boxes = boxes == null || boxes.isEmpty()
                ? bbox == null ? List.of() : List.of(bbox)
                : List.copyOf(boxes);
        targetText = targetText == null ? "" : targetText.trim();
        blockId = blockId == null ? "" : blockId;
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public enum Kind { TEXT_RANGE, FORMULA_REGION, VISUAL_REGION }
}
