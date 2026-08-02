package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.pdf.math.InlineMathTranscription;

import java.util.List;

/** One deterministic, page-addressable evidence item from a layout artifact. */
public record LayoutEvidence(String evidenceId,
                             Long paperId,
                             String blockId,
                             int page,
                             NormalizedBoundingBox bbox,
                             DocumentBlockRole role,
                             int readingOrder,
                             List<String> sectionPath,
                             String text,
                             double score,
                             boolean selected,
                             double confidence,
                             String documentHash,
                             String parserVersion,
                             DocumentBlockContentMode contentMode,
                             String structuredContent,
                             List<SelectionBlockRange> selectedRanges,
                             List<InlineMathTranscription> mathTranscriptions,
                             EvidenceOrigin origin,
                             EvidenceLocator locator,
                             List<String> retrievalRoutes) {

    public LayoutEvidence {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text;
        score = Math.max(0, Math.min(1, score));
        confidence = Math.max(0, Math.min(1, confidence));
        contentMode = contentMode == null ? DocumentBlockContentMode.TEXT : contentMode;
        structuredContent = structuredContent == null ? "" : structuredContent;
        selectedRanges = selectedRanges == null ? List.of() : List.copyOf(selectedRanges);
        mathTranscriptions = mathTranscriptions == null ? List.of() : List.copyOf(mathTranscriptions);
        origin = origin == null ? EvidenceOrigin.CURRENT_LAYOUT : origin;
        locator = locator == null ? new EvidenceLocator(bbox, text, defaultPrecision(role, contentMode)) : locator;
        retrievalRoutes = retrievalRoutes == null ? List.of() : retrievalRoutes.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /** Compatibility constructor for pre-locator callers. */
    public LayoutEvidence(String evidenceId,
                          Long paperId,
                          String blockId,
                          int page,
                          NormalizedBoundingBox bbox,
                          DocumentBlockRole role,
                          int readingOrder,
                          List<String> sectionPath,
                          String text,
                          double score,
                          boolean selected,
                          double confidence,
                          String documentHash,
                          String parserVersion,
                          DocumentBlockContentMode contentMode,
                          String structuredContent,
                          List<SelectionBlockRange> selectedRanges,
                          List<InlineMathTranscription> mathTranscriptions) {
        this(evidenceId, paperId, blockId, page, bbox, role, readingOrder, sectionPath,
                text, score, selected, confidence, documentHash, parserVersion, contentMode,
                structuredContent, selectedRanges, mathTranscriptions, EvidenceOrigin.CURRENT_LAYOUT,
                new EvidenceLocator(bbox, text, defaultPrecision(role, contentMode)), List.of());
    }

    /** Compatibility constructor for existing tests and deterministic callers. */
    public LayoutEvidence(String evidenceId,
                          Long paperId,
                          String blockId,
                          int page,
                          NormalizedBoundingBox bbox,
                          DocumentBlockRole role,
                          int readingOrder,
                          List<String> sectionPath,
                          String text,
                          double score,
                          boolean selected,
                          double confidence,
                          String documentHash,
                          String parserVersion) {
        this(evidenceId, paperId, blockId, page, bbox, role, readingOrder,
                sectionPath, text, score, selected, confidence, documentHash,
                parserVersion, DocumentBlockContentMode.TEXT, "", List.of(), List.of(),
                EvidenceOrigin.CURRENT_LAYOUT,
                new EvidenceLocator(bbox, text, EvidenceLocator.Precision.BLOCK), List.of());
    }

    /** Compatibility constructor for structured formula/table callers. */
    public LayoutEvidence(String evidenceId,
                          Long paperId,
                          String blockId,
                          int page,
                          NormalizedBoundingBox bbox,
                          DocumentBlockRole role,
                          int readingOrder,
                          List<String> sectionPath,
                          String text,
                          double score,
                          boolean selected,
                          double confidence,
                          String documentHash,
                          String parserVersion,
                          DocumentBlockContentMode contentMode,
                          String structuredContent) {
        this(evidenceId, paperId, blockId, page, bbox, role, readingOrder, sectionPath,
                text, score, selected, confidence, documentHash, parserVersion, contentMode,
                structuredContent, List.of(), List.of(), EvidenceOrigin.CURRENT_LAYOUT,
                new EvidenceLocator(bbox, text, defaultPrecision(role, contentMode)), List.of());
    }

    private static EvidenceLocator.Precision defaultPrecision(DocumentBlockRole role,
                                                               DocumentBlockContentMode mode) {
        if (role == DocumentBlockRole.FORMULA && mode == DocumentBlockContentMode.REGION) {
            return EvidenceLocator.Precision.FORMULA_REGION;
        }
        if (mode == DocumentBlockContentMode.REGION) return EvidenceLocator.Precision.VISUAL_REGION;
        return EvidenceLocator.Precision.BLOCK;
    }

    public LayoutEvidence withRetrieval(double retrievalScore, List<String> routes) {
        return new LayoutEvidence(evidenceId, paperId, blockId, page, bbox, role, readingOrder,
                sectionPath, text, retrievalScore, selected, confidence, documentHash, parserVersion,
                contentMode, structuredContent, selectedRanges, mathTranscriptions, origin, locator, routes);
    }
}
