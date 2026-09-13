package com.research.assistant.service.agent.core;

/** Provider-neutral model-call telemetry. Prompt and response contents are deliberately excluded. */
public record AgentModelCallTrace(
        int ordinal,
        String status,
        long durationMs,
        int messageCount,
        int toolCount,
        int estimatedPromptTokens,
        int promptTokens,
        int completionTokens,
        String finishReason,
        String errorType,
        int estimatedPromptTokensBefore,
        int cumulativeEstimatedPromptTokens,
        int cumulativePromptTokens,
        int maxEstimatedPromptTokens,
        int maxPromptTokens,
        boolean compacted,
        String responseKind,
        int responseTextCharacters
) {
    public AgentModelCallTrace(int ordinal, String status, long durationMs, int messageCount, int toolCount,
                               int promptTokens, int completionTokens, String finishReason, String errorType) {
        this(ordinal, status, durationMs, messageCount, toolCount, promptTokens,
                promptTokens, completionTokens, finishReason, errorType,
                promptTokens, promptTokens, promptTokens, promptTokens, promptTokens, false,
                null, 0);
    }

    /** Compatibility constructor for the pre-Harness trace shape. */
    public AgentModelCallTrace(int ordinal, String status, long durationMs, int messageCount, int toolCount,
                               int estimatedPromptTokens, int promptTokens, int completionTokens,
                               String finishReason, String errorType) {
        this(ordinal, status, durationMs, messageCount, toolCount, estimatedPromptTokens,
                promptTokens, completionTokens, finishReason, errorType,
                estimatedPromptTokens, estimatedPromptTokens, promptTokens,
                estimatedPromptTokens, promptTokens, false, null, 0);
    }

    /** Compatibility constructor for the pre-response-telemetry shape. */
    public AgentModelCallTrace(int ordinal, String status, long durationMs, int messageCount, int toolCount,
                               int estimatedPromptTokensBefore, int estimatedPromptTokensAfter,
                               int promptTokens, int completionTokens,
                               int cumulativeEstimatedPromptTokens, int cumulativePromptTokens,
                               int maxEstimatedPromptTokens, int maxPromptTokens,
                               String finishReason, String errorType, boolean compacted) {
        this(ordinal, status, durationMs, messageCount, toolCount, estimatedPromptTokensAfter,
                promptTokens, completionTokens, finishReason, errorType,
                estimatedPromptTokensBefore, cumulativeEstimatedPromptTokens, cumulativePromptTokens,
                maxEstimatedPromptTokens, maxPromptTokens, compacted, null, 0);
    }

    public AgentModelCallTrace(int ordinal, String status, long durationMs, int messageCount, int toolCount,
                               int estimatedPromptTokensBefore, int estimatedPromptTokensAfter,
                               int promptTokens, int completionTokens,
                               int cumulativeEstimatedPromptTokens, int cumulativePromptTokens,
                               int maxEstimatedPromptTokens, int maxPromptTokens,
                               String finishReason, String errorType, boolean compacted,
                               String responseKind, int responseTextCharacters) {
        this(ordinal, status, durationMs, messageCount, toolCount, estimatedPromptTokensAfter,
                promptTokens, completionTokens, finishReason, errorType,
                estimatedPromptTokensBefore, cumulativeEstimatedPromptTokens, cumulativePromptTokens,
                maxEstimatedPromptTokens, maxPromptTokens, compacted, responseKind, responseTextCharacters);
    }
}
