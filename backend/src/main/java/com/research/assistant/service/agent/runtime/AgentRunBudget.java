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
        // The Agent performs several sequential model/tool rounds.  Keep the durable
        // deadline above the per-request model timeout and reserve time for the final
        // answer plus persistence. maxModelCalls limits research decision calls;
        // the executor additionally reserves one provider round for the terminal
        // finish_research decision and one tool-free final-answer call. Call
        // counts remain emergency safeguards.
        return new AgentRunBudget(10, 12, 160_000, 900_000);
    }
}
