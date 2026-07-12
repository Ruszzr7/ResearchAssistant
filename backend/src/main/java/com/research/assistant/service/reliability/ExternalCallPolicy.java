package com.research.assistant.service.reliability;

import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Shared timeout, retry, concurrency and degradation policy for network-backed calls.
 * It is intentionally local-process only and does not require Redis.
 */
@Component
public class ExternalCallPolicy {

    private final ResearchMetrics metrics;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration permitWait;
    private final Semaphore permits;
    private final ExecutorService executor;

    @Autowired
    public ExternalCallPolicy(
            ResearchMetrics metrics,
            @Value("${app.external.timeout:30s}") Duration timeout,
            @Value("${app.external.max-attempts:2}") int maxAttempts,
            @Value("${app.external.retry-backoff:250ms}") Duration retryBackoff,
            @Value("${app.external.max-concurrency:8}") int maxConcurrency,
            @Value("${app.external.permit-wait:25ms}") Duration permitWait) {
        this.metrics = Objects.requireNonNull(metrics);
        this.timeout = safeDuration(timeout, Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMinutes(3));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 4));
        this.retryBackoff = safeDuration(retryBackoff, Duration.ofMillis(250), Duration.ZERO, Duration.ofSeconds(5));
        this.permitWait = safeDuration(permitWait, Duration.ofMillis(25), Duration.ZERO, Duration.ofSeconds(5));
        this.permits = new Semaphore(Math.max(1, Math.min(maxConcurrency, 32)));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "external-call-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    public ExternalCallPolicy() {
        this(new ResearchMetrics(new SimpleMeterRegistry()), Duration.ofSeconds(30), 2,
                Duration.ofMillis(250), 8, Duration.ofMillis(25));
    }

    public ExternalCallPolicy(ResearchMetrics metrics) {
        this(metrics, Duration.ofSeconds(30), 2, Duration.ofMillis(250), 8, Duration.ofMillis(25));
    }

    public <T> T execute(String operation, Callable<T> callable, Supplier<T> fallback) {
        return executeWithStatus(operation, callable, fallback).value();
    }

    public <T> ExternalCallResult<T> executeWithStatus(String operation,
                                                       Callable<T> callable,
                                                       Supplier<T> fallback) {
        long startedAt = metrics.startTimer();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(permitWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.externalCallFinished(operation, ExternalCallStatus.RATE_LIMITED.name().toLowerCase(),
                    0, startedAt);
            return new ExternalCallResult<>(fallback.get(), ExternalCallStatus.RATE_LIMITED, 0);
        }
        if (!acquired) {
            metrics.externalCallFinished(operation, ExternalCallStatus.RATE_LIMITED.name().toLowerCase(),
                    0, startedAt);
            return new ExternalCallResult<>(fallback.get(), ExternalCallStatus.RATE_LIMITED, 0);
        }

        ExternalCallStatus status = ExternalCallStatus.FAILED;
        int attempts = 0;
        try {
            for (attempts = 1; attempts <= maxAttempts; attempts++) {
                try {
                    T value = callWithTimeout(callable);
                    status = attempts == 1 ? ExternalCallStatus.SUCCESS : ExternalCallStatus.RETRIED_SUCCESS;
                    return new ExternalCallResult<>(value, status, attempts);
                } catch (TimeoutException e) {
                    status = ExternalCallStatus.TIMEOUT;
                } catch (Exception e) {
                    status = ExternalCallStatus.FAILED;
                }
                if (attempts < maxAttempts && !retryBackoff.isZero()) {
                    try {
                        Thread.sleep(retryBackoff.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            int completedAttempts = Math.min(attempts, maxAttempts);
            return new ExternalCallResult<>(fallback.get(), status, completedAttempts);
        } finally {
            permits.release();
            metrics.externalCallFinished(operation, status.name().toLowerCase(),
                    Math.max(1, Math.min(attempts, maxAttempts)), startedAt);
        }
    }

    public Duration timeout() {
        return timeout;
    }

    private <T> T callWithTimeout(Callable<T> callable) throws Exception {
        Future<T> future = executor.submit(callable);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private Duration safeDuration(Duration value, Duration fallback, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return fallback;
        }
        return value;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
