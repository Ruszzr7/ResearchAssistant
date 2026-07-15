package com.research.assistant.service.async;

import java.util.Map;
import java.util.function.Consumer;

/** 可恢复任务的运行时上下文；不持有线程池或数据库连接。 */
public record AsyncTaskExecutionContext(
        String taskId,
        String taskType,
        Map<String, Object> arguments,
        Consumer<String> stageUpdater,
        Consumer<Object> pendingUserUpdater,
        int attemptCount,
        int maxAttempts
) {

    public AsyncTaskExecutionContext {
        attemptCount = Math.max(1, attemptCount);
        maxAttempts = Math.max(attemptCount, maxAttempts);
    }

    public void stage(String text) {
        if (stageUpdater != null) {
            stageUpdater.accept(text);
        }
    }

    public void pendingUser(Object partialResult) {
        if (pendingUserUpdater != null) {
            pendingUserUpdater.accept(partialResult);
        }
    }

    public boolean isLastAttempt() {
        return attemptCount >= maxAttempts;
    }
}
