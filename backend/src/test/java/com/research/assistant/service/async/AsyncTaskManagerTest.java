package com.research.assistant.service.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncTaskManager} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskManagerTest {

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private AsyncTaskRecordMapper taskRecordMapper;

    @Mock
    private WorkflowStepMapper workflowStepMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Future<?> mockFuture;

    private AsyncTaskManager manager() {
        return new AsyncTaskManager(taskExecutor, taskRecordMapper, workflowStepMapper, objectMapper);
    }

    private void stubSyncExecution() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return mockFuture;
        }).when(taskExecutor).submit(any(Runnable.class));
    }

    private void stubInsertWithId(Long id) {
        doAnswer(invocation -> {
            AsyncTaskRecord record = invocation.getArgument(0);
            record.setId(id);
            return null;
        }).when(taskRecordMapper).insert(any(AsyncTaskRecord.class));
    }

    @Test
    void shouldCompleteTaskAndPersistResult() throws Exception {
        stubSyncExecution();
        stubInsertWithId(1L);
        when(objectMapper.writeValueAsString(any())).thenReturn("\"hello\"");

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> "hello");

        AsyncTaskResult<?> result = manager.get(taskId);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        assertThat(result.getResult()).isEqualTo("hello");

        verify(taskRecordMapper).insert(any(AsyncTaskRecord.class));
        verify(taskRecordMapper, atLeastOnce()).updateById(any(AsyncTaskRecord.class));
    }

    @Test
    void shouldFailTaskOnException() {
        stubSyncExecution();
        stubInsertWithId(1L);

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> { throw new RuntimeException("boom"); });

        AsyncTaskResult<?> result = manager.get(taskId);
        assertThat(result.getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(result.getError()).contains("boom");
    }

    @Test
    void shouldCancelPendingTask() {
        doReturn(mockFuture).when(taskExecutor).submit(any(Runnable.class));
        stubInsertWithId(1L);

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> "never");

        boolean cancelled = manager.cancel(taskId);

        assertThat(cancelled).isTrue();
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.CANCELLED);
        verify(mockFuture).cancel(true);
        verify(taskRecordMapper).updateById(any(AsyncTaskRecord.class));
    }

    @Test
    void shouldNotCancelCompletedTask() {
        stubSyncExecution();
        stubInsertWithId(1L);

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> "done");

        boolean cancelled = manager.cancel(taskId);

        assertThat(cancelled).isFalse();
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    }

    @Test
    void shouldRecoverTaskFromDatabaseAfterRestart() throws Exception {
        AsyncTaskRecord record = new AsyncTaskRecord();
        record.setId(42L);
        record.setTaskId("old-task");
        record.setStatus(AsyncTaskStatus.COMPLETED.name());
        record.setStageText("完成");
        record.setResultJson("\"recovered\"");
        record.setError(null);
        when(taskRecordMapper.selectByTaskId("old-task")).thenReturn(record);
        when(objectMapper.readValue("\"recovered\"", Object.class)).thenReturn("recovered");

        AsyncTaskManager manager = manager();
        AsyncTaskResult<?> result = manager.get("old-task");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        assertThat(result.getResult()).isEqualTo("recovered");
    }

    @Test
    void shouldMarkOrphanedTasksAsFailedOnStartup() {
        when(taskRecordMapper.update(any(), any())).thenReturn(3);

        AsyncTaskManager manager = manager();
        manager.markOrphanedTasksAsFailed();

        verify(taskRecordMapper).update(any(), any());
    }

    @Test
    void cancelledTaskShouldIgnoreLateWorkerCompletion() {
        AtomicReference<Runnable> runnable = new AtomicReference<>();
        AtomicBoolean executed = new AtomicBoolean();
        doAnswer(invocation -> {
            runnable.set(invocation.getArgument(0));
            return mockFuture;
        }).when(taskExecutor).submit(any(Runnable.class));
        stubInsertWithId(1L);

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> {
            executed.set(true);
            return "late result";
        });

        assertThat(manager.cancel(taskId)).isTrue();
        runnable.get().run();

        assertThat(executed).isFalse();
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.CANCELLED);
        assertThat(manager.get(taskId).getResult()).isNull();
    }

    @Test
    void rejectedSubmissionShouldBecomeTraceableFailure() {
        stubInsertWithId(1L);
        doAnswer(invocation -> { throw new TaskRejectedException("queue full"); })
                .when(taskExecutor).submit(any(Runnable.class));

        AsyncTaskManager manager = manager();
        String taskId = manager.submit(setStage -> "never");

        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(manager.get(taskId).getError()).contains("拒绝提交").contains("queue full");
    }

    @Test
    void shouldRejectDuplicateActiveTaskId() {
        doReturn(mockFuture).when(taskExecutor).submit(any(Runnable.class));
        stubInsertWithId(1L);
        AsyncTaskManager manager = manager();

        manager.submit("fixed-id", null, null, "test", (taskId, stage) -> "first");

        assertThatThrownBy(() -> manager.submit(
                "fixed-id", null, null, "test", (taskId, stage) -> "duplicate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能重复提交");
        verify(taskExecutor, times(1)).submit(any(Runnable.class));
    }

    @Test
    void shouldExpirePendingUserTaskAfterTtl() throws InterruptedException {
        stubSyncExecution();
        stubInsertWithId(1L);
        AsyncTaskManager manager = new AsyncTaskManager(
                taskExecutor, taskRecordMapper, workflowStepMapper, objectMapper, false,
                Duration.ofMillis(1), Duration.ofMinutes(30),
                new ResearchMetrics(new SimpleMeterRegistry()));

        String taskId = manager.submit("workflow", "{}", "confirm", (id, stage) -> {
            manager.setPendingUser(id, Map.of("awaiting", true));
            return null;
        });
        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.PENDING_USER);

        Thread.sleep(5);
        manager.expirePendingUserTasks();

        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.EXPIRED);
        assertThat(manager.get(taskId).getError()).contains("确认超时");
    }

    @Test
    void shouldFailAndInterruptTaskAfterExecutionTimeout() throws InterruptedException {
        doReturn(mockFuture).when(taskExecutor).submit(any(Runnable.class));
        stubInsertWithId(1L);
        AsyncTaskManager manager = new AsyncTaskManager(
                taskExecutor, taskRecordMapper, workflowStepMapper, objectMapper, false,
                Duration.ofHours(24), Duration.ofMillis(1),
                new ResearchMetrics(new SimpleMeterRegistry()));

        String taskId = manager.submit(stage -> "never");
        Thread.sleep(5);

        manager.timeoutActiveTasks();

        assertThat(manager.get(taskId).getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(manager.get(taskId).getError()).contains("执行超时");
        verify(mockFuture).cancel(true);
    }
}
