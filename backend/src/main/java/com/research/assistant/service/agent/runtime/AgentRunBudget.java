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
        // Keep the existing 90-second durable run boundary until real traces justify
        // changing it. Call counts are both persisted diagnostics and hard safeguards.
        return new AgentRunBudget(7, 10, 110_000, 90_000);
    }
}
