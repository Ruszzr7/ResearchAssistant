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

    /**
     * Executes with standard Skills whose metadata is visible initially and
     * whose scoped tools are supplied only after the model activates a Skill.
     * Older lightweight test gateways can keep implementing the basic method;
     * the LangChain4j adapter overrides this overload for real Skill support.
     */
    default AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                         List<AgentToolDefinition> tools,
                                         List<AgentSkillBinding> skills,
                                         ToolHandler handler,
                                         SkillActivationHandler activationHandler,
                                         ModelCallObserver observer) {
        return execute(messages, tools, handler, observer);
    }

    @FunctionalInterface
    interface ToolHandler {
        AgentToolExecution execute(AgentToolRequest request);
    }

    @FunctionalInterface
    interface SkillActivationHandler {
        void activated(String toolCallId, String skillName, String argumentsJson, String instructions);
    }

    @FunctionalInterface
    interface ModelCallObserver {
        void completed(AgentModelCallTrace trace);
    }
}
