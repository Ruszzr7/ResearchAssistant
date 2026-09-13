package com.research.assistant.service.agent.core;

public record AgentFrameworkResult(
        String content,
        int modelCalls,
        int toolCalls,
        int promptTokens,
        int completionTokens,
        int initialPromptTokens,
        int maxEstimatedPromptTokens,
        int cumulativeEstimatedPromptTokens,
        int maxPromptTokens
) {
    public AgentFrameworkResult(String content, int modelCalls, int toolCalls,
                                int promptTokens, int completionTokens) {
        this(content, modelCalls, toolCalls, promptTokens, completionTokens,
                0, 0, 0, 0);
    }
}
