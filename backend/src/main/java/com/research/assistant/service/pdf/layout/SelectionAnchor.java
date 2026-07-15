package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Version-bound link from a visible PDF selection to layout blocks. */
public record SelectionAnchor(Long paperId,
                              int page,
                              List<NormalizedBoundingBox> boxes,
                              String anchorText,
                              List<String> blockIds,
                              SelectionTokenRange tokenRange,
                              SelectionAnchorKind kind,
                              double confidence,
                              String documentHash,
                              String parserVersion) {

    public SelectionAnchor {
        boxes = boxes == null ? List.of() : List.copyOf(boxes);
        anchorText = anchorText == null ? "" : anchorText;
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        kind = kind == null ? SelectionAnchorKind.REGION : kind;
        confidence = Math.max(0, Math.min(1, confidence));
        documentHash = documentHash == null ? "" : documentHash;
        parserVersion = parserVersion == null ? "" : parserVersion;
    }
}
