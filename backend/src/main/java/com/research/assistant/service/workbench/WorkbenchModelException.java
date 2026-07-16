package com.research.assistant.service.workbench;

public class WorkbenchModelException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final String finishReason;
    private final int attemptCount;

    public WorkbenchModelException(String code, String message, boolean retryable) {
        this(code, message, retryable, 0, 0, 0, null, 0, null);
    }

    public WorkbenchModelException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, 0, 0, 0, null, 0, cause);
    }

    public WorkbenchModelException(String code,
                                   String message,
                                   boolean retryable,
                                   int promptTokens,
                                   int completionTokens,
                                   int totalTokens,
                                   String finishReason,
                                   int attemptCount) {
        this(code, message, retryable, promptTokens, completionTokens, totalTokens,
                finishReason, attemptCount, null);
    }

    private WorkbenchModelException(String code,
                                    String message,
                                    boolean retryable,
                                    int promptTokens,
                                    int completionTokens,
                                    int totalTokens,
                                    String finishReason,
                                    int attemptCount,
                                    Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.totalTokens = Math.max(this.promptTokens + this.completionTokens, totalTokens);
        this.finishReason = finishReason;
        this.attemptCount = Math.max(0, attemptCount);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public int promptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }
    public int totalTokens() { return totalTokens; }
    public String finishReason() { return finishReason; }
    public int attemptCount() { return attemptCount; }
}
