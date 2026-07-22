package com.research.assistant.service.pdf.layout;

/** PDF.js text-layer range supplied by the viewer; useful for exact UI restoration. */
public record ClientTextRange(int itemIndex,
                              int spanIndex,
                              int startOffset,
                              int endOffset) {

    public ClientTextRange {
        itemIndex = Math.max(0, itemIndex);
        spanIndex = Math.max(0, spanIndex);
        startOffset = Math.max(0, startOffset);
        endOffset = Math.max(startOffset, endOffset);
    }
}
