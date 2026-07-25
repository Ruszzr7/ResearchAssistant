package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class FormulaRegionGeometry {

    private FormulaRegionGeometry() { }

    static void validate(NormalizedBoundingBox box) {
        if (box == null || box.width() < 0.005 || box.height() < 0.003) {
            throw new IllegalArgumentException("公式区域太小，请重新框选");
        }
        if (area(box) > 0.35 || box.height() > 0.60) {
            throw new IllegalArgumentException("公式区域过大，请只框选目标公式");
        }
    }

    static String regionKey(int page, NormalizedBoundingBox box) {
        String canonical = String.format(Locale.ROOT, "%d|%.5f|%.5f|%.5f|%.5f",
                page, box.x(), box.y(), box.width(), box.height());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static double bestOverlap(List<NormalizedBoundingBox> selected, NormalizedBoundingBox candidate) {
        return selected == null ? 0 : selected.stream()
                .mapToDouble(box -> overlap(box, candidate)).max().orElse(0);
    }

    static double overlap(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        double width = Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        double height = Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.y(), second.y()));
        double intersection = width * height;
        double denominator = Math.max(0.000001, Math.min(area(first), area(second)));
        return Math.max(0, Math.min(1, intersection / denominator));
    }

    static double intersectionOverUnion(NormalizedBoundingBox first,
                                        NormalizedBoundingBox second) {
        double width = Math.max(0, Math.min(first.right(), second.right())
                - Math.max(first.x(), second.x()));
        double height = Math.max(0, Math.min(first.bottom(), second.bottom())
                - Math.max(first.y(), second.y()));
        double intersection = width * height;
        double union = area(first) + area(second) - intersection;
        return union <= 0 ? 0 : Math.max(0, Math.min(1, intersection / union));
    }

    static double area(NormalizedBoundingBox box) {
        return Math.max(0, box.width()) * Math.max(0, box.height());
    }
}
