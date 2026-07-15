package com.research.assistant.service.pdf.layout;

/**
 * Page-relative bounding box using a top-left origin and values in [0, 1].
 */
public record NormalizedBoundingBox(double x, double y, double width, double height) {

    public NormalizedBoundingBox {
        x = clamp(x);
        y = clamp(y);
        width = clamp(width);
        height = clamp(height);
        if (x + width > 1) {
            width = Math.max(0, 1 - x);
        }
        if (y + height > 1) {
            height = Math.max(0, 1 - y);
        }
    }

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }
}
