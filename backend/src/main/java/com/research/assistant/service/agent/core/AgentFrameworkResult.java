package com.research.assistant.service.agent.core;

public record AgentFrameworkResult(
        String content,
        int modelCalls,
        int toolCalls,
        int promptTokens,
        int completionTokens
) {
}
