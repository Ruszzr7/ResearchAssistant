package com.research.assistant.service.pdf.layout;

/** Coarse page lane assigned by the layout parser. */
public enum DocumentLayoutLane {
    SINGLE,
    LEFT,
    RIGHT,
    FULL,
    MARGIN,
    UNKNOWN;

    static DocumentLayoutLane infer(NormalizedBoundingBox box) {
        if (box == null) return UNKNOWN;
        if (box.width() >= 0.52 || (box.x() < 0.48 && box.right() > 0.52)) return FULL;
        if (box.right() <= 0.52) return LEFT;
        if (box.x() >= 0.48) return RIGHT;
        return UNKNOWN;
    }
}
