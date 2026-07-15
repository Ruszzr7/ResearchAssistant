package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Explainable quality score used by the adaptive layout parser. */
public record LayoutQualityReport(double score,
                                  boolean fallbackRecommended,
                                  int textCharacters,
                                  double validGeometryRatio,
                                  double cleanTextRatio,
                                  double averageBlockConfidence,
                                  List<LayoutQualityIssue> issues) {
    public LayoutQualityReport {
        score = clamp(score);
        validGeometryRatio = clamp(validGeometryRatio);
        cleanTextRatio = clamp(cleanTextRatio);
        averageBlockConfidence = clamp(averageBlockConfidence);
        textCharacters = Math.max(0, textCharacters);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
