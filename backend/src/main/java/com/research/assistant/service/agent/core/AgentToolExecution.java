package com.research.assistant.service.agent.core;

import java.util.Set;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Collections;

public record AgentToolExecution(String resultJson, Set<String> sourceObjectIds,
                                 List<AgentVisualContent> visuals) {
    public AgentToolExecution(String resultJson, Set<String> sourceObjectIds) {
        this(resultJson, sourceObjectIds, List.of());
    }

    public AgentToolExecution {
        if (resultJson == null) throw new IllegalArgumentException("tool result is required");
        sourceObjectIds = sourceObjectIds == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(sourceObjectIds));
        visuals = visuals == null ? List.of() : List.copyOf(visuals);
    }
}
