package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Auditable parser selection and quality decision persisted with an artifact. */
public record LayoutArtifactProvenance(String primaryParser,
                                       String selectedParser,
                                       boolean fallbackEligible,
                                       boolean fallbackAttempted,
                                       boolean fallbackAccepted,
                                       double primaryQuality,
                                       Double fallbackQuality,
                                       List<String> qualityIssues,
                                       String fallbackFailureCode) {

    public LayoutArtifactProvenance {
        primaryParser = safe(primaryParser, "unknown");
        selectedParser = safe(selectedParser, primaryParser);
        primaryQuality = clamp(primaryQuality);
        fallbackQuality = fallbackQuality == null ? null : clamp(fallbackQuality);
        qualityIssues = qualityIssues == null ? List.of() : qualityIssues.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(16)
                .toList();
        fallbackFailureCode = safe(fallbackFailureCode, "");
    }

    public static LayoutArtifactProvenance direct(String parser, double quality) {
        return new LayoutArtifactProvenance(
                parser, parser, false, false, false, quality, null, List.of(), "");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
