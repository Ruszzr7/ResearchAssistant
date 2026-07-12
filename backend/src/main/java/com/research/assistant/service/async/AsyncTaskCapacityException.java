package com.research.assistant.service.async;

/** 异步任务队列达到保护阈值时抛出，由接口层映射为 429。 */
public class AsyncTaskCapacityException extends RuntimeException {

    public AsyncTaskCapacityException(String message) {
        super(message);
    }
}
