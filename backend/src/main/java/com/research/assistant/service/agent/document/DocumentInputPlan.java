package com.research.assistant.service.agent.document;

import java.util.List;

public record DocumentInputPlan(DocumentInputMode primaryMode, List<DocumentInputMode> fallbackModes,
                                String reason) {
    public DocumentInputPlan { fallbackModes = fallbackModes == null ? List.of() : List.copyOf(fallbackModes); }
}
