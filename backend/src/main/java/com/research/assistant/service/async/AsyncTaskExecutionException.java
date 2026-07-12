package com.research.assistant.service.async;

/** 处理器用于声明错误是否值得自动重试及其稳定错误码。 */
public class AsyncTaskExecutionException extends RuntimeException {

    private final boolean retryable;
    private final String failureCode;

    public AsyncTaskExecutionException(String failureCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
        this.retryable = retryable;
    }

    public AsyncTaskExecutionException(String failureCode, String message, boolean retryable) {
        this(failureCode, message, retryable, null);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
