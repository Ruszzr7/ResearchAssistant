package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.mapper.AgentRunMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/** Enforces the durable wall-clock deadline even when a provider call is stuck. */
@Service
public class AgentRunTimeoutService {

    private final AgentRunMapper runMapper;
    private final AgentRuntimeService runtimeService;

    public AgentRunTimeoutService(AgentRunMapper runMapper, AgentRuntimeService runtimeService) {
        this.runMapper = runMapper;
        this.runtimeService = runtimeService;
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
            runtimeService.transitionRun(run.getRunId(), AgentRunStatus.FAILED, null,
                    code, messagePrefix + run.getTimeoutMs() + " ms");
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
}
