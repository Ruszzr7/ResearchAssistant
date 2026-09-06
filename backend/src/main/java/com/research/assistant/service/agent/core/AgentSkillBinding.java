package com.research.assistant.service.agent.core;

import dev.langchain4j.skills.Skill;

import java.util.List;

/**
 * One discovered standard Skill and the application host tools exposed after
 * that Skill is activated. The model-facing progressive disclosure remains
 * owned by LangChain4j's {@code Skills} implementation.
 */
public record AgentSkillBinding(Skill skill, List<AgentToolDefinition> tools) {

    public AgentSkillBinding {
        if (skill == null) throw new IllegalArgumentException("skill is required");
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public String name() {
        return skill.name();
    }

    public String description() {
        return skill.description();
    }
}
