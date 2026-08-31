package com.research.assistant.service.agent.source;

import java.util.List;

public record GroundedAnswer(
        String answer,
        List<CitationBinding> bindings,
        List<CitationBinding> evidenceEntries
) {
    public GroundedAnswer {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        evidenceEntries = evidenceEntries == null ? List.of() : List.copyOf(evidenceEntries);
    }
}
