package com.research.assistant.service.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoverableTaskManagerTest {

    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
    private final WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
    private final Future<?> future = mock(Future.class);
    private final AtomicReference<AsyncTaskRecord> stored = new AtomicReference<>();
    private final AsyncTaskHandlerRegistry registry = new AsyncTaskHandlerRegistry();
    private AsyncTaskManager manager;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return future;
        }).when(executor).submit(any(Runnable.class));
        doAnswer(invocation -> {
            AsyncTaskRecord record = invocation.getArgument(0);
            record.setId(7L);
            stored.set(record);
            return 1;
        }).when(taskMapper).insert(any(AsyncTaskRecord.class));
        when(taskMapper.selectByTaskId(any())).thenAnswer(invocation -> stored.get());
        when(taskMapper.selectByIdempotencyKey(any())).thenAnswer(invocation -> {
            AsyncTaskRecord record = stored.get();
            return record != null && invocation.getArgument(0).equals(record.getIdempotencyKey()) ? record : null;
        });
        doAnswer(invocation -> {
            AsyncTaskRecord record = stored.get();
            record.setStatus(AsyncTaskStatus.PROCESSING.name());
            record.setLeaseOwner(invocation.getArgument(1));
            record.setAttemptCount((record.getAttemptCount() == null ? 0 : record.getAttemptCount()) + 1);
            return 1;
        }).when(taskMapper).claimForExecution(any(), any(), any());
        doReturn(1).when(taskMapper).update(any(), any());
        manager = new AsyncTaskManager(executor, taskMapper, stepMapper, new ObjectMapper(), registry);
    }

    @Test
    void shouldExecuteRecoverableHandlerAndDeduplicateByKey() {
        registry.register("test", context -> "ok");

        String taskId = manager.submitRecoverable("test", null, "Test", java.util.Map.of("value", 1), "key-1");
        String duplicate = manager.submitRecoverable("test", null, "Test", java.util.Map.of("value", 1), "key-1");

        assertThat(duplicate).isEqualTo(taskId);
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    void shouldPutRetryableFailureIntoRetryWait() {
        registry.register("flaky", context -> {
            throw new AsyncTaskExecutionException("REMOTE", "provider unavailable", true);
        });

        String taskId = manager.submitRecoverable("flaky", null, "Flaky", java.util.Map.of(), null);

        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.RETRY_WAIT);
        assertThat(manager.get(taskId).getError()).contains("provider unavailable");
    }

    @Test
    void shouldDispatchRetryWaitTaskAgain() {
        AtomicInteger executions = new AtomicInteger();
        registry.register("retry-again", context -> {
            executions.incrementAndGet();
            throw new AsyncTaskExecutionException("REMOTE", "provider unavailable", true);
        });

        String taskId = manager.submitRecoverable("retry-again", null, "Retry", java.util.Map.of(), null);
        stored.get().setStatus(AsyncTaskStatus.RETRY_WAIT.name());
        when(taskMapper.selectDispatchable(anyInt())).thenReturn(java.util.List.of(stored.get()));

        manager.dispatchRecoverableTasks();

        assertThat(executions).hasValue(2);
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.RETRY_WAIT);
    }

    @Test
    void shouldMoveRetryableFailureToDeadLetterAtLimit() {
        registry.register("dead", context -> {
            throw new AsyncTaskExecutionException("REMOTE", "provider unavailable", true);
        });
        stored.set(null);

        String taskId = manager.submitRecoverable("dead", null, "Dead", java.util.Map.of(), null);
        stored.get().setMaxAttempts(1);
        when(taskMapper.selectDispatchable(anyInt())).thenReturn(java.util.List.of(stored.get()));

        // The first execution already consumes the only attempt.
        manager.dispatchRecoverableTasks();

        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.DEAD_LETTER);
    }

    @Test
    void shouldRejectSubmissionWhenRecoverableQueueIsFull() {
        ReflectionTestUtils.setField(manager, "maxQueueDepth", 1);
        when(taskMapper.countRecoverableActive()).thenReturn(1L);

        assertThatThrownBy(() -> manager.submitRecoverable(
                "full", null, "Full", java.util.Map.of(), null))
                .isInstanceOf(AsyncTaskCapacityException.class);
        org.mockito.Mockito.verify(executor, never()).submit(any(Runnable.class));
    }

    @Test
    void shouldRejectIdempotencyKeyReuseWithDifferentArguments() {
        registry.register("same-key", context -> "ok");
        String taskId = manager.submitRecoverable("same-key", null, "Same", java.util.Map.of("value", 1), "key-2");

        assertThatThrownBy(() -> manager.submitRecoverable(
                "same-key", null, "Same", java.util.Map.of("value", 2), "key-2"))
                .isInstanceOf(AsyncTaskIdempotencyConflictException.class);
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    void shouldAllowOnlyOneNodeToClaimTheSameRecoverableTask() throws Exception {
        registry.register("shared", context -> "ok");
        AsyncTaskRecord pending = new AsyncTaskRecord();
        pending.setId(11L);
        pending.setTaskId("shared-task");
        pending.setTaskType("shared");
        pending.setStatus(AsyncTaskStatus.PENDING.name());
        pending.setAttemptCount(0);
        pending.setMaxAttempts(3);
        stored.set(pending);

        doReturn(future).when(executor).submit(any(Runnable.class));
        when(taskMapper.selectDispatchable(anyInt())).thenReturn(java.util.List.of(pending));
        when(taskMapper.countRecoverableProcessing()).thenReturn(0L);
        doAnswer(invocation -> {
            synchronized (pending) {
                if (!AsyncTaskStatus.PENDING.name().equals(pending.getStatus())
                        && !AsyncTaskStatus.RETRY_WAIT.name().equals(pending.getStatus())) {
                    return 0;
                }
                pending.setStatus(AsyncTaskStatus.PROCESSING.name());
                pending.setLeaseOwner(invocation.getArgument(1));
                pending.setAttemptCount(pending.getAttemptCount() + 1);
                return 1;
            }
        }).when(taskMapper).claimForExecution(any(), any(), any());

        AsyncTaskManager secondNode = new AsyncTaskManager(executor, taskMapper, stepMapper,
                new ObjectMapper(), registry);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(manager::dispatchRecoverableTasks);
            var second = callers.submit(secondNode::dispatchRecoverableTasks);
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        assertThat(pending.getStatus()).isEqualTo(AsyncTaskStatus.PROCESSING.name());
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        verify(taskMapper, times(2)).claimForExecution(any(), any(), any());
        verify(executor, times(1)).submit(any(Runnable.class));
    }
}
