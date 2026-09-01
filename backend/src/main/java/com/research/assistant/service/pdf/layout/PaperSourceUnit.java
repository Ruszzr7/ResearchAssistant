package com.research.assistant.service.pdf.layout;

import java.util.List;

/** A same-page compound source assembled without changing its original blocks. */
public record PaperSourceUnit(String id,
                              Kind kind,
                              String label,
                              int page,
                              List<String> sectionPath,
                              String text,
                              List<DocumentBlock> blocks,
                              List<NormalizedBoundingBox> boxes,
                              double confidence) {

    public PaperSourceUnit {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id is required");
        if (page <= 0) throw new IllegalArgumentException("source unit page must be positive");
        kind = kind == null ? Kind.ALGORITHM : kind;
        label = label == null ? "" : label.strip();
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        boxes = boxes == null ? List.of() : List.copyOf(boxes);
        if (blocks.isEmpty() || boxes.isEmpty()) {
            throw new IllegalArgumentException("source unit requires blocks and boxes");
        }
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public enum Kind { FORMULA_FAMILY, ALGORITHM, FIGURE, TABLE }
}
