package com.research.assistant.service.agent.core;

public record AgentToolDefinition(String name, String description, String parametersJsonSchema) {
    public AgentToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        if (parametersJsonSchema == null || parametersJsonSchema.isBlank()) {
            throw new IllegalArgumentException("tool parameter schema is required");
        }
        description = description == null ? "" : description;
    }
}
