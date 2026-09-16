package com.research.assistant.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.service.agent.core.AgentTurnSubmissionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/** Enforces the durable wall-clock deadline even when a provider call is stuck. */
@Service
public class AgentRunTimeoutService {

    private final AgentRunMapper runMapper;
    private final AgentRuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final AgentTurnSubmissionService submissionService;

    /** Constructor retained for focused tests and small embedders. */
    public AgentRunTimeoutService(AgentRunMapper runMapper, AgentRuntimeService runtimeService) {
        this(runMapper, runtimeService, new ObjectMapper(), null);
    }

    public AgentRunTimeoutService(AgentRunMapper runMapper, AgentRuntimeService runtimeService,
                                  ObjectMapper objectMapper) {
        this(runMapper, runtimeService, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunTimeoutService(AgentRunMapper runMapper, AgentRuntimeService runtimeService,
                                  ObjectMapper objectMapper,
                                  AgentTurnSubmissionService submissionService) {
        this.runMapper = runMapper;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
        this.submissionService = submissionService;
    }

    @Scheduled(fixedDelayString = "${app.agent.timeout-scan-ms:5000}",
            initialDelayString = "${app.agent.timeout-scan-ms:5000}")
    public void timeoutExpiredRuns() {
        LocalDateTime now = LocalDateTime.now();
        for (AgentRunRecord run : runMapper.selectQueued()) {
            if (!expiredFrom(run.getCreatedAt(), run, now)) continue;
            transitionExpired(run, "QUEUE_TIMEOUT", "agent run queue exceeded ");
        }
        for (AgentRunRecord run : runMapper.selectRunning()) {
            LocalDateTime start = run.getStartedAt();
            if (start == null) continue;
            if (!expiredFrom(start, run, now)) continue;
            transitionExpired(run, "RUN_TIMEOUT", "agent run exceeded ");
        }
    }

    private boolean expiredFrom(LocalDateTime start, AgentRunRecord run, LocalDateTime now) {
        return start != null && run.getTimeoutMs() != null
                && Duration.between(start, now).toMillis() >= run.getTimeoutMs();
    }

    private void transitionExpired(AgentRunRecord run, String code, String messagePrefix) {
        try {
            AgentRunFailureClassifier.Failure failure = "RUN_TIMEOUT".equals(code)
                    ? timeoutFailure(run)
                    : AgentRunFailureClassifier.fromCode(code);
            runtimeService.transitionRun(run.getRunId(), AgentRunStatus.FAILED, null,
                    failure.code(), "RUN_TIMEOUT".equals(code)
                            ? timeoutMessage(failure, run) : messagePrefix + run.getTimeoutMs() + " ms");
            if (submissionService != null) submissionService.cancelExecution(run.getRunId());
        } catch (AgentRuntimeConflictException | IllegalStateException alreadyFinished) {
            AgentRunRecord latest = null;
            try {
                latest = runtimeService.getRun(run.getRunId());
            } catch (RuntimeException ignored) {
                // The run may have been removed by a cleanup operation.
            }
            if (latest != null && (AgentRunStatus.RUNNING.name().equals(latest.getStatus())
                    || AgentRunStatus.QUEUED.name().equals(latest.getStatus()))) {
                throw alreadyFinished;
            }
            // A concurrent completion or another terminal transition wins over
            // this stale timeout candidate.
        }
    }

    /**
     * A watchdog can win the same race as the worker's exception handler. Use the
     * last content-free model trace to preserve a local guard failure instead of
     * relabelling it as a provider timeout.
     */
    private AgentRunFailureClassifier.Failure timeoutFailure(AgentRunRecord run) {
        return AgentRunFailureClassifier.classifyTimeoutTrace(readTrace(run.getModelTraceJson()),
                run.getMaxModelCalls());
    }

    private String timeoutMessage(AgentRunFailureClassifier.Failure failure, AgentRunRecord run) {
        if ("RUN_TIMEOUT".equals(failure.code())) {
            return "agent run exceeded " + run.getTimeoutMs() + " ms";
        }
        return failure.code() + ": " + failure.userMessage();
    }

    private JsonNode readTrace(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }
}
