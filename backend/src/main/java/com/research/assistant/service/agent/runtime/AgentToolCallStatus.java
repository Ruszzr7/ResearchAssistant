package com.research.assistant.service.agent.runtime;

public enum AgentToolCallStatus {
    REQUESTED,
    RUNNING,
    WAITING_CLIENT,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
