package com.research.assistant.service.async;

/**
 * 异步任务状态。
 */
public enum AsyncTaskStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PENDING_USER,
    EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public boolean canTransitionTo(AsyncTaskStatus next) {
        if (next == null || this == next || isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> next == PROCESSING || next == FAILED || next == CANCELLED;
            case PROCESSING -> next == COMPLETED || next == FAILED
                    || next == CANCELLED || next == PENDING_USER;
            case PENDING_USER -> next == CANCELLED || next == EXPIRED;
            default -> false;
        };
    }

    public boolean canRestart() {
        return this == FAILED || this == CANCELLED || this == EXPIRED || this == PENDING_USER;
    }
}
