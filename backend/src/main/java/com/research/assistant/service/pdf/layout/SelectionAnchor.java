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
                              String parserVersion,
                              SelectionMappingStatus mappingStatus,
                              SelectionContentType contentType,
                              SelectionEvidenceUse evidenceUse,
                              List<SelectionBlockRange> blockRanges,
                              ClientTextAnchor clientTextAnchor) {

    public SelectionAnchor {
        boxes = boxes == null ? List.of() : List.copyOf(boxes);
        anchorText = anchorText == null ? "" : anchorText;
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        kind = kind == null ? SelectionAnchorKind.REGION : kind;
        confidence = Math.max(0, Math.min(1, confidence));
        documentHash = documentHash == null ? "" : documentHash;
        parserVersion = parserVersion == null ? "" : parserVersion;
        mappingStatus = mappingStatus == null
                ? (kind == SelectionAnchorKind.REGION ? SelectionMappingStatus.REGION : SelectionMappingStatus.PARTIAL)
                : mappingStatus;
        contentType = contentType == null ? SelectionContentType.UNKNOWN : contentType;
        evidenceUse = evidenceUse == null
                ? (mappingStatus == SelectionMappingStatus.REGION
                    ? SelectionEvidenceUse.VISUAL_ONLY : SelectionEvidenceUse.CLAIM_EVIDENCE)
                : evidenceUse;
        blockRanges = blockRanges == null ? List.of() : List.copyOf(blockRanges);
    }

    /** Compatibility constructor for persisted requests and existing integrations. */
    public SelectionAnchor(Long paperId,
                           int page,
                           List<NormalizedBoundingBox> boxes,
                           String anchorText,
                           List<String> blockIds,
                           SelectionTokenRange tokenRange,
                           SelectionAnchorKind kind,
                           double confidence,
                           String documentHash,
                           String parserVersion) {
        this(paperId, page, boxes, anchorText, blockIds, tokenRange, kind, confidence,
                documentHash, parserVersion, null, null, null, List.of(), null);
    }
}
