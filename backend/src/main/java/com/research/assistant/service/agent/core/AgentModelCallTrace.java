package com.research.assistant.service.agent.core;

/** Provider-neutral model-call telemetry. Prompt and response contents are deliberately excluded. */
public record AgentModelCallTrace(
        int ordinal,
        String status,
        long durationMs,
        int messageCount,
        int toolCount,
        int promptTokens,
        int completionTokens,
        String finishReason,
        String errorType
) {
}
