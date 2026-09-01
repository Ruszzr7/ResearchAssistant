package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Explainable quality score used by the adaptive layout parser. */
public record LayoutQualityReport(double score,
                                  boolean fallbackRecommended,
                                  int textCharacters,
                                  double validGeometryRatio,
                                  double cleanTextRatio,
                                  double averageBlockConfidence,
                                  double readingOrderScore,
                                  List<LayoutQualityIssue> issues) {
    public LayoutQualityReport {
        score = clamp(score);
        validGeometryRatio = clamp(validGeometryRatio);
        cleanTextRatio = clamp(cleanTextRatio);
        averageBlockConfidence = clamp(averageBlockConfidence);
        readingOrderScore = clamp(readingOrderScore);
        textCharacters = Math.max(0, textCharacters);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** Compatibility constructor for callers created before geometric order scoring. */
    public LayoutQualityReport(double score,
                               boolean fallbackRecommended,
                               int textCharacters,
                               double validGeometryRatio,
                               double cleanTextRatio,
                               double averageBlockConfidence,
                               List<LayoutQualityIssue> issues) {
        this(score, fallbackRecommended, textCharacters, validGeometryRatio, cleanTextRatio,
                averageBlockConfidence, 1, issues);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
