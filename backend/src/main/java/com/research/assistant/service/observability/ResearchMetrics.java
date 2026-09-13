package com.research.assistant.service.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目级低基数指标。标签只描述操作和结果，不写入论文内容、提示词或凭据。
 */
@Component
public class ResearchMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicInteger recoverableQueueDepth = new AtomicInteger();
    private final AtomicInteger recoverableInFlight = new AtomicInteger();

    public ResearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("research.async.tasks.active", activeTasks, AtomicInteger::get)
                .description("In-process asynchronous tasks that have not reached a terminal state")
                .register(registry);
        Gauge.builder("research.async.queue.depth", recoverableQueueDepth, AtomicInteger::get)
                .description("Persisted recoverable tasks waiting or running")
                .register(registry);
        Gauge.builder("research.async.tasks.inflight", recoverableInFlight, AtomicInteger::get)
                .description("Recoverable tasks currently claimed by this process")
                .register(registry);
    }

    public long startTimer() {
        return System.nanoTime();
    }

    public void taskSubmitted(String taskType) {
        activeTasks.incrementAndGet();
        counter("research.async.tasks", "type", taskType(taskType), "outcome", "submitted").increment();
    }

    public void taskFinished(String taskType, String outcome, long startedAtNanos) {
        activeTasks.updateAndGet(value -> Math.max(0, value - 1));
        String type = taskType(taskType);
        counter("research.async.tasks", "type", type, "outcome", outcome).increment();
        timer("research.async.task.duration", "type", type, "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
    }

    public void aiFinished(String operation, String outcome, long startedAtNanos) {
        counter("research.ai.calls", "operation", operation, "outcome", outcome).increment();
        timer("research.ai.call.duration", "operation", operation, "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
    }

    public void addTokens(String tokenType, int count) {
        if (count > 0) {
            counter("research.ai.tokens", "type", tokenType).increment(count);
        }
    }

    public void synthesisQualityFinished(String operation, String outcome) {
        counter("research.synthesis.quality", "operation", safeOutcome(operation),
                "outcome", safeOutcome(outcome)).increment();
    }

    public void externalCallFinished(String operation, String outcome, int attempts, long startedAtNanos) {
        counter("research.external.calls", "operation", safeOutcome(operation),
                "outcome", safeOutcome(outcome)).increment();
        counter("research.external.attempts", "operation", safeOutcome(operation))
                .increment(Math.max(1, attempts));
        timer("research.external.duration", "operation", safeOutcome(operation),
                "outcome", safeOutcome(outcome))
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
    }

    public void updateRecoverableQueueDepth(long depth) {
        recoverableQueueDepth.set((int) Math.min(Integer.MAX_VALUE, Math.max(0, depth)));
    }

    public void updateRecoverableInFlight(int inFlight) {
        recoverableInFlight.set(Math.max(0, inFlight));
    }

    public void taskCapacityRejected(String taskType) {
        counter("research.async.capacity", "type", taskType(taskType), "outcome", "rejected").increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }

    private String taskType(String taskType) {
        return taskType == null || taskType.isBlank() ? "general" : taskType;
    }

    private String safeOutcome(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
