package com.research.assistant.service.reliability;

public record ExternalCallResult<T>(T value, ExternalCallStatus status, int attempts) {
}
