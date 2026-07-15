package com.research.assistant.service.pdf.layout;

/**
 * Stable semantic roles used by layout artifacts and evidence filtering.
 *
 * <p>The geometric parser assigns conservative roles first; the semantic
 * enricher then refines title, abstract, heading, formula and reference roles.</p>
 */
public enum DocumentBlockRole {
    TITLE,
    AUTHOR,
    ABSTRACT,
    HEADING,
    BODY,
    FIGURE,
    CAPTION,
    FORMULA,
    TABLE,
    REFERENCE,
    HEADER,
    FOOTER,
    MARGIN_METADATA
}
