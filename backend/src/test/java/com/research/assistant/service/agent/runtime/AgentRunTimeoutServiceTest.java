package com.research.assistant.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.service.agent.core.AgentTurnSubmissionService;
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
    void cancelsTheActiveWorkerAfterTheDurableTimeoutWins() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentTurnSubmissionService submissions = mock(AgentTurnSubmissionService.class);
        AgentRunRecord expired = run("expired", LocalDateTime.now().minusSeconds(190), 180_000L);
        when(mapper.selectRunning()).thenReturn(List.of(expired));
        when(mapper.selectQueued()).thenReturn(List.of());

        new AgentRunTimeoutService(mapper, runtime, new ObjectMapper(), submissions).timeoutExpiredRuns();

        verify(runtime).transitionRun("expired", AgentRunStatus.FAILED, null,
                "RUN_TIMEOUT", "agent run exceeded 180000 ms");
        verify(submissions).cancelExecution("expired");
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

    @Test
    void preservesAContextGuardWhenTheWatchdogWinsTheWorkerRace() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRunRecord expired = run("context", LocalDateTime.now().minusSeconds(130), 120_000L);
        expired.setMaxModelCalls(7);
        expired.setModelTraceJson("[{\"ordinal\":7,\"status\":\"FAILED\","
                + "\"estimatedPromptTokens\":16209,\"responseKind\":\"NOT_SENT\"}]");
        when(mapper.selectRunning()).thenReturn(List.of(expired));
        when(mapper.selectQueued()).thenReturn(List.of());

        new AgentRunTimeoutService(mapper, runtime).timeoutExpiredRuns();

        verify(runtime).transitionRun("context", AgentRunStatus.FAILED, null,
                "CONTEXT_BUDGET_EXCEEDED", "CONTEXT_BUDGET_EXCEEDED: 当前上下文内容过长，请缩小输入范围");
    }

    @Test
    void preservesTheCallGuardWhenTheLastModelRequestWasNotSent() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentRunRecord expired = run("calls", LocalDateTime.now().minusSeconds(130), 120_000L);
        expired.setMaxModelCalls(7);
        expired.setModelTraceJson("[{\"ordinal\":8,\"status\":\"FAILED\","
                + "\"estimatedPromptTokens\":11000,\"responseKind\":\"NOT_SENT\"}]");
        when(mapper.selectRunning()).thenReturn(List.of(expired));
        when(mapper.selectQueued()).thenReturn(List.of());

        new AgentRunTimeoutService(mapper, runtime).timeoutExpiredRuns();

        verify(runtime).transitionRun("calls", AgentRunStatus.FAILED, null,
                "AGENT_CALL_LIMIT", "AGENT_CALL_LIMIT: 论文助手调用次数已达上限，请缩小问题范围后重试");
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
