package com.research.assistant.service.pdf.layout;

/** Version-bound target used by the PDF viewer; targetText is optional and must come from the same block. */
public record EvidenceLocator(NormalizedBoundingBox targetBbox,
                              java.util.List<NormalizedBoundingBox> targetBoxes,
                              String targetText,
                              Precision precision) {
    public EvidenceLocator {
        targetBoxes = targetBoxes == null || targetBoxes.isEmpty()
                ? targetBbox == null ? java.util.List.of() : java.util.List.of(targetBbox)
                : java.util.List.copyOf(targetBoxes);
        targetText = targetText == null ? "" : targetText.trim();
        precision = precision == null ? Precision.BLOCK : precision;
    }

    public EvidenceLocator(NormalizedBoundingBox targetBbox,
                           String targetText,
                           Precision precision) {
        this(targetBbox, targetBbox == null ? java.util.List.of() : java.util.List.of(targetBbox),
                targetText, precision);
    }

    public enum Precision {
        BLOCK,
        TEXT_RANGE,
        TEXT_SPAN,
        FORMULA_REGION,
        VISUAL_REGION
    }
}
