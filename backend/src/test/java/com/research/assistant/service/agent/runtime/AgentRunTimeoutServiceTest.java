package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.mapper.AgentRunMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunTimeoutServiceTest {

    @Test
    void expiresOnlyRunsPastTheirWallClockDeadline() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRunRecord expired = run("expired", LocalDateTime.now().minusSeconds(130), 120_000L);
        AgentRunRecord active = run("active", LocalDateTime.now().minusSeconds(10), 120_000L);
        when(mapper.selectRunning()).thenReturn(List.of(expired, active));
        when(mapper.selectQueued()).thenReturn(List.of());

        new AgentRunTimeoutService(mapper, runtime).timeoutExpiredRuns();

        verify(runtime).transitionRun("expired", AgentRunStatus.FAILED, null,
                "RUN_TIMEOUT", "agent run exceeded 120000 ms");
        verify(runtime, never()).transitionRun(org.mockito.ArgumentMatchers.eq("active"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ignoresAStaleTimeoutWhenTheRunAlreadyReachedATerminalState() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRunRecord expired = run("expired", LocalDateTime.now().minusSeconds(130), 120_000L);
        AgentRunRecord completed = run("expired", LocalDateTime.now().minusSeconds(130), 120_000L);
        completed.setStatus(AgentRunStatus.COMPLETED.name());
        when(mapper.selectRunning()).thenReturn(List.of(expired));
        when(mapper.selectQueued()).thenReturn(List.of());
        when(runtime.getRun("expired")).thenReturn(completed);
        org.mockito.Mockito.doThrow(new IllegalStateException("invalid AgentRun transition"))
                .when(runtime).transitionRun("expired", AgentRunStatus.FAILED, null,
                        "RUN_TIMEOUT", "agent run exceeded 120000 ms");

        new AgentRunTimeoutService(mapper, runtime).timeoutExpiredRuns();

        verify(runtime).getRun("expired");
    }

    @Test
    void expiresQueuedRunsUsingCreationTimeAndKeepsRunDeadlineSeparate() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRunRecord queued = run("queued", null, 120_000L);
        queued.setStatus(AgentRunStatus.QUEUED.name());
        queued.setCreatedAt(LocalDateTime.now().minusSeconds(130));
        when(mapper.selectQueued()).thenReturn(List.of(queued));
        when(mapper.selectRunning()).thenReturn(List.of());

        new AgentRunTimeoutService(mapper, runtime).timeoutExpiredRuns();

        verify(runtime).transitionRun("queued", AgentRunStatus.FAILED, null,
                "QUEUE_TIMEOUT", "agent run queue exceeded 120000 ms");
    }

    private AgentRunRecord run(String id, LocalDateTime startedAt, long timeoutMs) {
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId(id);
        run.setStatus("RUNNING");
        run.setStartedAt(startedAt);
        run.setTimeoutMs(timeoutMs);
        return run;
    }
}
