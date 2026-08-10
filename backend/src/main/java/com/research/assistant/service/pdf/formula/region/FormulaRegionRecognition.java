package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;

import java.util.List;

/** User-facing result for a formula region; only CONFIRMED results carry a usable anchor. */
public record FormulaRegionRecognition(
        Long id,
        Long paperId,
        int page,
        NormalizedBoundingBox bbox,
        String latex,
        List<String> formulas,
        double confidence,
        FormulaRegionSource source,
        FormulaRegionStatus status,
        String previewDataUrl,
        String message,
        SelectionAnchor anchor,
        boolean confirmed) {

    public FormulaRegionRecognition {
        latex = latex == null ? "" : latex;
        formulas = formulas == null ? List.of() : formulas.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .limit(8)
                .toList();
        if (formulas.isEmpty() && !latex.isBlank()) formulas = List.of(latex);
        confidence = Math.max(0, Math.min(1, confidence));
        previewDataUrl = previewDataUrl == null ? "" : previewDataUrl;
        message = message == null ? "" : message;
        confirmed = status == FormulaRegionStatus.CONFIRMED && anchor != null;
    }

    /** Backward-compatible constructor for callers that only have one formula. */
    public FormulaRegionRecognition(Long id,
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
        this(id, paperId, page, bbox, latex,
                latex == null || latex.isBlank() ? List.of() : List.of(latex),
                confidence, source, status, previewDataUrl, message, anchor, confirmed);
    }
}
