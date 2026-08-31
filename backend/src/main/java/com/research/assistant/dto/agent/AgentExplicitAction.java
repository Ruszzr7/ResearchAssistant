package com.research.assistant.dto.agent;

import java.util.Map;

public record AgentExplicitAction(String type, Map<String, Object> parameters) {
    public AgentExplicitAction {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("action type is required");
        type = type.trim().toUpperCase();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
