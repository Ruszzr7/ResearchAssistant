package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Resolved selection plus its bounded, policy-filtered local evidence. */
public record LocalEvidenceResult(SelectionAnchor anchor,
                                  List<LayoutEvidence> evidence,
                                  boolean regionFallback) {

    public LocalEvidenceResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
