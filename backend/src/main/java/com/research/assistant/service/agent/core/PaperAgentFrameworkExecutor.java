package com.research.assistant.service.agent.core;

import java.util.List;

/**
 * Executes one agent invocation. The implementation owns the generic model/tool
 * loop; domain services only provide tool definitions and handlers.
 */
public interface PaperAgentFrameworkExecutor {

    AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                 List<AgentToolDefinition> tools,
                                 ToolHandler handler);

    default AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                         List<AgentToolDefinition> tools,
                                         ToolHandler handler,
                                         ModelCallObserver observer) {
        return execute(messages, tools, handler);
    }

    @FunctionalInterface
    interface ToolHandler {
        String execute(AgentToolRequest request);
    }

    @FunctionalInterface
    interface ModelCallObserver {
        void completed(AgentModelCallTrace trace);
    }
}
