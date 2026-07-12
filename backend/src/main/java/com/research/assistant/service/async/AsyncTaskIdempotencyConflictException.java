package com.research.assistant.service.async;

/** 同一幂等键对应了不同请求参数。 */
public class AsyncTaskIdempotencyConflictException extends RuntimeException {

    public AsyncTaskIdempotencyConflictException(String message) {
        super(message);
    }
}
