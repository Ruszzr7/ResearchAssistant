package com.research.assistant.service.workbench;

import com.research.assistant.service.async.AsyncTaskExecutionContext;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import com.research.assistant.service.async.AsyncTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchExecutionServiceTest {

    private AsyncTaskManager asyncTaskManager;
    private AsyncTaskHandlerRegistry registry;
    private WorkbenchRunTraceService traceService;
    private WorkbenchExecutionEngine engine;
    private WorkbenchExecutionService service;

    @BeforeEach
    void setUp() {
        asyncTaskManager = mock(AsyncTaskManager.class);
        registry = new AsyncTaskHandlerRegistry();
        traceService = mock(WorkbenchRunTraceService.class);
        engine = mock(WorkbenchExecutionEngine.class);
        service = new WorkbenchExecutionService(asyncTaskManager, registry, traceService, engine);
    }

    @Test
    void submitsOneRecoverableTaskBoundToTheRun() {
        WorkbenchRunTrace trace = trace(WorkbenchRunStatus.PLANNED, null);
        when(traceService.requireTrace("run-1")).thenReturn(trace);

        WorkbenchExecutionService.Submission submission = service.submit("run-1");

        assertThat(submission.runId()).isEqualTo("run-1");
        assertThat(submission.taskId()).isNotBlank();
        assertThat(submission.status()).isEqualTo(WorkbenchRunStatus.QUEUED);
        verify(traceService).markQueued("run-1", submission.taskId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(asyncTaskManager).submitRecoverable(
                eq(submission.taskId()), eq(WorkbenchExecutionService.TASK_TYPE),
                eq(WorkbenchPlan.Workflow.SELECTION_QA.name()), eq("选区问答"),
                arguments.capture(), eq("workbench-run:run-1"));
        assertThat(arguments.getValue()).containsEntry("runId", "run-1");
    }

    @Test
    void repeatedSubmissionReturnsExistingTaskWithoutEnqueueingAgain() {
        WorkbenchRunTrace queued = trace(WorkbenchRunStatus.QUEUED, "task-existing");
        when(traceService.requireTrace("run-1")).thenReturn(queued);

        WorkbenchExecutionService.Submission submission = service.submit("run-1");

        assertThat(submission.taskId()).isEqualTo("task-existing");
        verify(asyncTaskManager, never()).submitRecoverable(
                anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void registeredHandlerReconstructsExecutionOnlyFromPersistedRunId() throws Exception {
        WorkbenchWorkflowResult expected = mock(WorkbenchWorkflowResult.class);
        when(engine.execute(eq("run-1"), eq("task-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        AsyncTaskExecutionContext context = new AsyncTaskExecutionContext(
                "task-1", WorkbenchExecutionService.TASK_TYPE, Map.of("runId", "run-1"),
                ignored -> { }, ignored -> { }, 1, 3);

        Object result = registry.get(WorkbenchExecutionService.TASK_TYPE).execute(context);

        assertThat(result).isSameAs(expected);
        verify(engine).execute(eq("run-1"), eq("task-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lastRecoverableAttemptAlsoTerminatesTheBoundWorkbenchRun() {
        WorkbenchRunTrace running = trace(WorkbenchRunStatus.RUNNING, "task-1");
        when(traceService.requireTrace("run-1")).thenReturn(running);
        when(engine.execute(eq("run-1"), eq("task-1"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AsyncTaskExecutionException(
                        "MODEL_CALL_FAILED", "模型服务暂时不可用", true));
        AsyncTaskExecutionContext context = new AsyncTaskExecutionContext(
                "task-1", WorkbenchExecutionService.TASK_TYPE, Map.of("runId", "run-1"),
                ignored -> { }, ignored -> { }, 3, 3);

        assertThatThrownBy(() -> registry.get(WorkbenchExecutionService.TASK_TYPE).execute(context))
                .isInstanceOf(AsyncTaskExecutionException.class);

        verify(traceService).failRun("run-1", "MODEL_CALL_FAILED", "模型服务暂时不可用");
    }

    private WorkbenchRunTrace trace(WorkbenchRunStatus status, String taskId) {
        WorkbenchRunTrace trace = mock(WorkbenchRunTrace.class);
        WorkbenchPlan plan = mock(WorkbenchPlan.class);
        when(plan.workflow()).thenReturn(WorkbenchPlan.Workflow.SELECTION_QA);
        when(trace.runId()).thenReturn("run-1");
        when(trace.taskId()).thenReturn(taskId);
        when(trace.status()).thenReturn(status);
        when(trace.plan()).thenReturn(plan);
        return trace;
    }
}
