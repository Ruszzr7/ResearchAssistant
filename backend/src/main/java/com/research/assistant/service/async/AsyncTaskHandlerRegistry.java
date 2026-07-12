package com.research.assistant.service.async;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 任务类型到可恢复处理器的进程内注册表。任务数据本身仍以 MySQL 为准。 */
@Component
public class AsyncTaskHandlerRegistry {

    private final Map<String, AsyncTaskHandler> handlers = new ConcurrentHashMap<>();

    public void register(String taskType, AsyncTaskHandler handler) {
        if (taskType == null || taskType.isBlank() || handler == null) {
            throw new IllegalArgumentException("taskType 和 handler 不能为空");
        }
        AsyncTaskHandler previous = handlers.putIfAbsent(taskType, handler);
        if (previous != null && previous != handler) {
            throw new IllegalStateException("重复注册异步任务处理器: " + taskType);
        }
    }

    public AsyncTaskHandler get(String taskType) {
        return handlers.get(taskType);
    }

    public boolean contains(String taskType) {
        return handlers.containsKey(taskType);
    }
}
