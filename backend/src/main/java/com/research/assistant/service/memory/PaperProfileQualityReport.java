package com.research.assistant.service.memory;

import java.util.List;
import java.util.Map;

/** Deterministic acceptance result for a generated, versioned paper profile. */
public record PaperProfileQualityReport(boolean usable,
                                        boolean ready,
                                        List<String> issues,
                                        Map<String, Integer> counts) {
    public PaperProfileQualityReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }
}
