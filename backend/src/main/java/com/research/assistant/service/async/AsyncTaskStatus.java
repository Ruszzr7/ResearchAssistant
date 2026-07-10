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
    PENDING_USER;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
