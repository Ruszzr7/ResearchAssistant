package com.research.assistant.service.pdf.layout;

/** Character offsets within one canonical layout block. */
public record SelectionBlockRange(String blockId, int start, int end) {

    public SelectionBlockRange {
        blockId = blockId == null ? "" : blockId;
        start = Math.max(0, start);
        end = Math.max(start, end);
    }
}
