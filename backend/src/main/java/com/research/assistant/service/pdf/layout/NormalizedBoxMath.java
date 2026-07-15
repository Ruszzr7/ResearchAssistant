package com.research.assistant.service.pdf.layout;

final class NormalizedBoxMath {

    private NormalizedBoxMath() {
    }

    static double area(NormalizedBoundingBox box) {
        return box == null ? 0 : Math.max(0, box.width()) * Math.max(0, box.height());
    }

    static double intersectionArea(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first == null || second == null) {
            return 0;
        }
        double width = Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        double height = Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.y(), second.y()));
        return width * height;
    }

    static double selectionCoverage(NormalizedBoundingBox selection, NormalizedBoundingBox block) {
        double area = area(selection);
        return area <= 0 ? 0 : intersectionArea(selection, block) / area;
    }
}
