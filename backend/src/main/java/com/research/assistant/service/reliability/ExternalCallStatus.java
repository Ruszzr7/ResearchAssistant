package com.research.assistant.service.reliability;

public enum ExternalCallStatus {
    SUCCESS,
    RETRIED_SUCCESS,
    TIMEOUT,
    RATE_LIMITED,
    FAILED
}
