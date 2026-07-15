package com.research.assistant.service.workbench;

public class WorkbenchModelException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public WorkbenchModelException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public WorkbenchModelException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
