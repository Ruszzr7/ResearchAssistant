package com.research.assistant.service.agent.core;

import java.util.List;

/**
 * A small, optional capability bundle exposed to the model. A skill describes
 * when a capability is useful and contributes its atomic tools; it does not
 * prescribe an execution workflow.
 */
public record AgentSkill(String id, String description, String instructions,
                         List<AgentToolDefinition> tools) {
    public AgentSkill {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("skill id is required");
        description = description == null ? "" : description.trim();
        instructions = instructions == null ? "" : instructions.trim();
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
