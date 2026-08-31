package com.research.assistant.service.agent.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentStateMachine {

    private static final Map<AgentRunStatus, Set<AgentRunStatus>> RUN_TRANSITIONS = runTransitions();
    private static final Map<AgentToolCallStatus, Set<AgentToolCallStatus>> TOOL_TRANSITIONS = toolTransitions();

    private AgentStateMachine() { }

    public static void requireRunTransition(AgentRunStatus from, AgentRunStatus to) {
        if (!RUN_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("invalid AgentRun transition: " + from + " -> " + to);
        }
    }

    public static void requireToolTransition(AgentToolCallStatus from, AgentToolCallStatus to) {
        if (!TOOL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("invalid ToolCall transition: " + from + " -> " + to);
        }
    }

    private static Map<AgentRunStatus, Set<AgentRunStatus>> runTransitions() {
        Map<AgentRunStatus, Set<AgentRunStatus>> map = new EnumMap<>(AgentRunStatus.class);
        map.put(AgentRunStatus.QUEUED, EnumSet.of(AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        map.put(AgentRunStatus.RUNNING, EnumSet.of(AgentRunStatus.WAITING_USER, AgentRunStatus.WAITING_CLIENT,
                AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        map.put(AgentRunStatus.WAITING_USER, EnumSet.of(AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        map.put(AgentRunStatus.WAITING_CLIENT, EnumSet.of(AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        return Map.copyOf(map);
    }

    private static Map<AgentToolCallStatus, Set<AgentToolCallStatus>> toolTransitions() {
        Map<AgentToolCallStatus, Set<AgentToolCallStatus>> map = new EnumMap<>(AgentToolCallStatus.class);
        map.put(AgentToolCallStatus.REQUESTED, EnumSet.of(AgentToolCallStatus.RUNNING,
                AgentToolCallStatus.WAITING_CLIENT, AgentToolCallStatus.FAILED, AgentToolCallStatus.CANCELLED));
        map.put(AgentToolCallStatus.RUNNING, EnumSet.of(AgentToolCallStatus.WAITING_CLIENT,
                AgentToolCallStatus.COMPLETED, AgentToolCallStatus.FAILED, AgentToolCallStatus.CANCELLED));
        map.put(AgentToolCallStatus.WAITING_CLIENT, EnumSet.of(AgentToolCallStatus.RUNNING,
                AgentToolCallStatus.COMPLETED, AgentToolCallStatus.FAILED, AgentToolCallStatus.CANCELLED));
        return Map.copyOf(map);
    }
}
