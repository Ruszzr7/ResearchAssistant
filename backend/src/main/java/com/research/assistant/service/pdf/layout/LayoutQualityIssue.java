package com.research.assistant.service.pdf.layout;

/** Stable, non-sensitive reasons for a layout fallback decision. */
public enum LayoutQualityIssue {
    NO_TEXT_BLOCKS,
    SPARSE_TEXT_COVERAGE,
    CORRUPTED_TEXT,
    INVALID_GEOMETRY,
    LOW_BLOCK_CONFIDENCE,
    UNSTABLE_READING_ORDER,
    LOW_LAYOUT_CONFIDENCE
}
