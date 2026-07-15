package com.research.assistant.service.pdf.layout;

/** Optional character offsets inside a single matched block. */
public record SelectionTokenRange(int start, int end) {

    public SelectionTokenRange {
        start = Math.max(0, start);
        end = Math.max(start, end);
    }
}
