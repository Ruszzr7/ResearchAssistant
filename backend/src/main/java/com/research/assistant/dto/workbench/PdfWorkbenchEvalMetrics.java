package com.research.assistant.dto.workbench;

import java.time.Instant;
import java.util.List;

/** Deterministic and optional real-PDF regression results; never contains paper text or paths. */
public record PdfWorkbenchEvalMetrics(int schemaVersion,
                                      Instant evaluatedAt,
                                      int deterministicCases,
                                      int deterministicPassed,
                                      int realCases,
                                      int realExecuted,
                                      int realPassed,
                                      int realSkipped,
                                      double passRate,
                                      List<CaseResult> cases) {

    public PdfWorkbenchEvalMetrics {
        cases = cases == null ? List.of() : List.copyOf(cases);
        passRate = clamp(passRate);
    }

    public boolean allDeterministicPassed() {
        return deterministicCases > 0 && deterministicPassed == deterministicCases;
    }

    public record CaseResult(String id,
                             String suite,
                             Status status,
                             List<String> issues,
                             long durationMs) {
        public CaseResult {
            id = safe(id, "unknown");
            suite = safe(suite, "unknown");
            status = status == null ? Status.FAILED : status;
            issues = issues == null ? List.of() : issues.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .limit(20)
                    .toList();
            durationMs = Math.max(0, durationMs);
        }
    }

    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
