package com.research.assistant.service.pdf.layout;

import java.util.List;

/** A conservative cross-page link between independently addressable source units. */
public record PaperSourceContinuation(String id,
                                      PaperSourceUnit.Kind kind,
                                      String label,
                                      String text,
                                      List<PaperSourceUnit> parts,
                                      double confidence) {
    public PaperSourceContinuation {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("continuation id is required");
        if (kind == null) throw new IllegalArgumentException("continuation kind is required");
        label = label == null ? "" : label.strip();
        text = text == null ? "" : text.strip();
        parts = parts == null ? List.of() : List.copyOf(parts);
        if (parts.size() < 2) throw new IllegalArgumentException("continuation requires at least two parts");
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
