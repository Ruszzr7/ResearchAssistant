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

    public ResearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("research.async.tasks.active", activeTasks, AtomicInteger::get)
                .description("In-process asynchronous tasks that have not reached a terminal state")
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

    public void ragIndexFinished(String outcome, int chunkCount, long startedAtNanos) {
        counter("research.rag.index", "outcome", outcome).increment();
        if (chunkCount > 0) {
            counter("research.rag.indexed.chunks").increment(chunkCount);
        }
        timer("research.rag.index.duration", "outcome", outcome)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
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
}
