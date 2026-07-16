package com.research.assistant.service.translation;

import org.springframework.http.HttpStatus;

public class TranslationException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    public TranslationException(String code, String message, HttpStatus status, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
