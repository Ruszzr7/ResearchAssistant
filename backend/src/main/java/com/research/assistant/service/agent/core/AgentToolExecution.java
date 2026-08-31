package com.research.assistant.service.agent.core;

import java.util.Set;

public record AgentToolExecution(String resultJson, Set<String> sourceObjectIds) {
    public AgentToolExecution {
        if (resultJson == null) throw new IllegalArgumentException("tool result is required");
        sourceObjectIds = sourceObjectIds == null ? Set.of() : Set.copyOf(sourceObjectIds);
    }
}
