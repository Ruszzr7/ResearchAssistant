package com.research.assistant.service.async;

/**
 * 异步任务状态。
 */
public enum AsyncTaskStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
    CANCELLED,
    PENDING_USER,
    EXPIRED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == EXPIRED || this == DEAD_LETTER;
    }

    public boolean canTransitionTo(AsyncTaskStatus next) {
        if (next == null || this == next || isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> next == PROCESSING || next == FAILED || next == CANCELLED;
            case PROCESSING -> next == COMPLETED || next == FAILED
                    || next == CANCELLED || next == PENDING_USER || next == RETRY_WAIT
                    || next == DEAD_LETTER;
            case RETRY_WAIT -> next == PROCESSING || next == FAILED || next == CANCELLED;
            case PENDING_USER -> next == CANCELLED || next == EXPIRED;
            default -> false;
        };
    }

    public boolean canRestart() {
        return this == FAILED || this == CANCELLED || this == EXPIRED
                || this == DEAD_LETTER || this == PENDING_USER;
    }
}
