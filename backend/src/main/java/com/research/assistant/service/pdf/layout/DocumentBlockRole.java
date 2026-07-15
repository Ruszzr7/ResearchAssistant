package com.research.assistant.service.pdf.layout;

/**
 * Stable semantic roles used by layout artifacts and evidence filtering.
 *
 * <p>P1-B1 only assigns conservative geometric roles. Rich roles such as
 * ABSTRACT, HEADING and REFERENCE are refined by the next classifier slice.</p>
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
