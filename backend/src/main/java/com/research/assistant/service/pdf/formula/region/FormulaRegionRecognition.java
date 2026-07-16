package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;

/** User-facing result for a formula region; only CONFIRMED results carry a usable anchor. */
public record FormulaRegionRecognition(
        Long id,
        Long paperId,
        int page,
        NormalizedBoundingBox bbox,
        String latex,
        double confidence,
        FormulaRegionSource source,
        FormulaRegionStatus status,
        String previewDataUrl,
        String message,
        SelectionAnchor anchor,
        boolean confirmed) {

    public FormulaRegionRecognition {
        latex = latex == null ? "" : latex;
        confidence = Math.max(0, Math.min(1, confidence));
        previewDataUrl = previewDataUrl == null ? "" : previewDataUrl;
        message = message == null ? "" : message;
        confirmed = status == FormulaRegionStatus.CONFIRMED && anchor != null;
    }
}
