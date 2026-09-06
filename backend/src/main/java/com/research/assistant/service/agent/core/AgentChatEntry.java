package com.research.assistant.service.agent.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentChatEntry(Role role, String content, String toolCallId, String toolName,
                             Map<String, Object> attributes) {

    public AgentChatEntry(Role role, String content, String toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, Map.of());
    }

    public enum Role { SYSTEM, USER, ASSISTANT, ASSISTANT_TOOL, TOOL }

    public AgentChatEntry {
        if (role == null) throw new IllegalArgumentException("role is required");
        content = content == null ? "" : content;
        attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static AgentChatEntry system(String content) { return new AgentChatEntry(Role.SYSTEM, content, null, null); }
    public static AgentChatEntry user(String content) { return new AgentChatEntry(Role.USER, content, null, null); }
    public static AgentChatEntry assistant(String content) { return new AgentChatEntry(Role.ASSISTANT, content, null, null); }
    public static AgentChatEntry assistantTool(String id, String name, String argumentsJson) {
        return new AgentChatEntry(Role.ASSISTANT_TOOL, argumentsJson, id, name);
    }
    public static AgentChatEntry tool(String id, String name, String content) { return new AgentChatEntry(Role.TOOL, content, id, name); }
    public static AgentChatEntry tool(String id, String name, String content,
                                      Map<String, Object> attributes) {
        return new AgentChatEntry(Role.TOOL, content, id, name, attributes);
    }
}
