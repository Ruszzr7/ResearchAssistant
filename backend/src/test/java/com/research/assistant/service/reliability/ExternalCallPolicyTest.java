package com.research.assistant.service.reliability;

import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalCallPolicyTest {

    private final ExternalCallPolicy policy = new ExternalCallPolicy(
            new ResearchMetrics(new SimpleMeterRegistry()),
            Duration.ofMillis(100), 2, Duration.ZERO, 2, Duration.ZERO);

    @AfterEach
    void close() {
        policy.shutdown();
    }

    @Test
    void shouldRetryAndReportRetriedSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        ExternalCallResult<String> result = policy.executeWithStatus("test_source", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary");
            }
            return "ok";
        }, () -> "fallback");

        assertEquals("ok", result.value());
        assertEquals(ExternalCallStatus.RETRIED_SUCCESS, result.status());
        assertEquals(2, result.attempts());
    }

    @Test
    void shouldTimeoutAndReturnFallback() {
        ExternalCallResult<String> result = policy.executeWithStatus("slow_source", () -> {
            Thread.sleep(250);
            return "late";
        }, () -> "fallback");

        assertEquals("fallback", result.value());
        assertEquals(ExternalCallStatus.TIMEOUT, result.status());
        assertEquals(2, result.attempts());
    }
}
