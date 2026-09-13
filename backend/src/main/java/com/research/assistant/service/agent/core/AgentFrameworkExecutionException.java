package com.research.assistant.service.agent.core;

/**
 * Keeps provider-independent usage available when an Agent invocation fails
 * before it can return its normal framework result.
 */
final class AgentFrameworkExecutionException extends RuntimeException {
    private final AgentFrameworkResult usage;

    AgentFrameworkExecutionException(Throwable cause, AgentFrameworkResult usage) {
        super(cause == null || cause.getMessage() == null ? "Agent framework execution failed" : cause.getMessage(),
                cause);
        this.usage = usage;
    }

    AgentFrameworkResult usage() {
        return usage;
    }
}
