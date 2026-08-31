package com.research.assistant.service.agent.core;

public record AgentChatEntry(Role role, String content, String toolCallId, String toolName) {
    public enum Role { SYSTEM, USER, ASSISTANT, ASSISTANT_TOOL, TOOL }

    public AgentChatEntry {
        if (role == null) throw new IllegalArgumentException("role is required");
        content = content == null ? "" : content;
    }

    public static AgentChatEntry system(String content) { return new AgentChatEntry(Role.SYSTEM, content, null, null); }
    public static AgentChatEntry user(String content) { return new AgentChatEntry(Role.USER, content, null, null); }
    public static AgentChatEntry assistant(String content) { return new AgentChatEntry(Role.ASSISTANT, content, null, null); }
    public static AgentChatEntry assistantTool(String id, String name, String argumentsJson) {
        return new AgentChatEntry(Role.ASSISTANT_TOOL, argumentsJson, id, name);
    }
    public static AgentChatEntry tool(String id, String name, String content) { return new AgentChatEntry(Role.TOOL, content, id, name); }
}
