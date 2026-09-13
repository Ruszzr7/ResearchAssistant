package com.research.assistant.service.agent.core;

import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentTurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnSubmissionServiceTest {

    @Test
    void returnsPersistedRunningStateBeforeTheModelLoopExecutes() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskExecutor executor = queued::set;
        AgentTurnSubmissionService service = new AgentTurnSubmissionService(loop, executor);
        AgentTurnInput input = new AgentTurnInput(7L, 9L, "普通问题", null, null,
                List.of(), List.of(), null, "request-1", null);
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        run.setStatus("RUNNING");
        AgentLoopService.PreparedTurn prepared = new AgentLoopService.PreparedTurn(
                input, mock(AgentContextSnapshot.class), new AgentTurnRecord(), run, false);
        AgentTurnResult accepted = new AgentTurnResult(
                "turn-1", "run-1", "RUNNING", null, List.of(), List.of());
        when(loop.prepare(input)).thenReturn(prepared);
        when(loop.currentResult("run-1")).thenReturn(accepted);

        AgentTurnResult result = service.submit(input);

        assertThat(result).isSameAs(accepted);
        assertThat(queued.get()).isNotNull();
        verify(loop, org.mockito.Mockito.never()).executePrepared(prepared);

        queued.get().run();
        verify(loop).executePrepared(prepared);
    }

    @Test
    void duplicateSubmissionReturnsItsStoredResultWithoutDispatchingAgain() {
        AgentLoopService loop = mock(AgentLoopService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AgentTurnSubmissionService service = new AgentTurnSubmissionService(loop, executor);
        AgentTurnInput input = new AgentTurnInput(7L, null, "重复问题", null, null,
                List.of(), List.of(), null, "request-1", null);
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        AgentLoopService.PreparedTurn prepared = new AgentLoopService.PreparedTurn(
                input, mock(AgentContextSnapshot.class), new AgentTurnRecord(), run, true);
        AgentTurnResult stored = new AgentTurnResult(
                "turn-1", "run-1", "COMPLETED", "已有回答", List.of(), List.of());
        when(loop.prepare(input)).thenReturn(prepared);
        when(loop.currentResult("run-1")).thenReturn(stored);

        assertThat(service.submit(input)).isSameAs(stored);
        verify(executor, org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancellationInterruptsTheLocalFutureWhenTheExecutorSupportsIt() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        Future<?> future = mock(Future.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AgentTurnInput input = new AgentTurnInput(7L, 9L, "可取消问题", null, null,
                List.of(), List.of(), null, "request-cancel", null);
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        run.setStatus("RUNNING");
        AgentLoopService.PreparedTurn prepared = new AgentLoopService.PreparedTurn(
                input, mock(AgentContextSnapshot.class), new AgentTurnRecord(), run, false);
        when(loop.prepare(input)).thenReturn(prepared);
        when(loop.currentResult("run-1")).thenReturn(new AgentTurnResult(
                "turn-1", "run-1", "RUNNING", null, List.of(), List.of()));
        when(executor.submit(org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(0));
                    return future;
                });
        AgentTurnSubmissionService service = new AgentTurnSubmissionService(loop, executor);

        service.submit(input);
        service.cancelExecution("run-1");

        assertThat(queued.get()).isNotNull();
        verify(future).cancel(true);
    }
}
