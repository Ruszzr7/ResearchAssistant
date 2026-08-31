package com.research.assistant.service.agent.runtime;

public record AgentRunBudget(int maxModelCalls, int maxToolCalls, int tokenBudget, long timeoutMs) {
    public AgentRunBudget {
        if (maxModelCalls < 1 || maxToolCalls < 1 || tokenBudget < 1 || timeoutMs < 1) {
            throw new IllegalArgumentException("all run budgets must be positive");
        }
    }

    public static AgentRunBudget defaults() {
        // This is the default runtime input budget for the single-paper agent.
        // It is deliberately a broad resource budget, not a per-skill workflow limit.
        // The agent provider allows one request to run for up to 60 seconds.
        // Keep a 90-second durable run boundary as a final liveness safeguard;
        // model/tool call counts are recorded for diagnostics, not workflow control.
        return new AgentRunBudget(8, 20, 110_000, 90_000);
    }
}
