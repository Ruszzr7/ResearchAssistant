package com.research.assistant.service.agent.core;

public record AgentToolRequest(String id, String name, String argumentsJson) {
    public AgentToolRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("tool request id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}
