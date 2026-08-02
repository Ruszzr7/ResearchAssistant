package com.research.assistant.service.pdf.layout;

/** Version-bound target used by the PDF viewer; targetText is optional and must come from the same block. */
public record EvidenceLocator(NormalizedBoundingBox targetBbox,
                              String targetText,
                              Precision precision) {
    public EvidenceLocator {
        targetText = targetText == null ? "" : targetText.trim();
        precision = precision == null ? Precision.BLOCK : precision;
    }

    public enum Precision {
        BLOCK,
        TEXT_RANGE,
        FORMULA_REGION,
        VISUAL_REGION
    }
}
