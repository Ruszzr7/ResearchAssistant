package com.research.assistant.service.memory;

/** Preserves provider usage when a bounded paper-memory generation cannot be accepted. */
final class PaperMemoryGenerationException extends RuntimeException {

    private final int promptTokens;
    private final int completionTokens;
    private final String finishReason;

    PaperMemoryGenerationException(String message,
                                   Throwable cause,
                                   int promptTokens,
                                   int completionTokens,
                                   String finishReason) {
        super(message, cause);
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.finishReason = finishReason == null ? "" : finishReason;
    }

    int promptTokens() {
        return promptTokens;
    }

    int completionTokens() {
        return completionTokens;
    }

    String finishReason() {
        return finishReason;
    }
}
