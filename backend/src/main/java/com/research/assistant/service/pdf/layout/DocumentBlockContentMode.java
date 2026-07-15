package com.research.assistant.service.pdf.layout;

/**
 * How precisely a layout block may be consumed as evidence.
 *
 * <p>{@link #REGION} is deliberately not treated as extracted text. It only
 * guarantees a page-addressable visual area and must be checked against the
 * rendered PDF before making symbol- or cell-level claims.</p>
 */
public enum DocumentBlockContentMode {
    TEXT,
    STRUCTURED,
    REGION
}
