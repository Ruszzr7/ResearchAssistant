package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.service.ai.plan.PlanExecutor;
import com.research.assistant.service.ai.plan.Planner;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskStatus;
import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import com.research.assistant.service.rag.RagIndexingException;
import com.research.assistant.service.rag.RagIndexingResult;
import com.research.assistant.service.rag.RagIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncTaskServiceTest {

    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
    private final WorkflowStepMapper stepMapper = mock(WorkflowStepMapper.class);
    private final Future<?> future = mock(Future.class);
    private final RagIndexingService ragIndexingService = mock(RagIndexingService.class);
    private final AtomicReference<AsyncTaskRecord> storedRecord = new AtomicReference<>();
    private AsyncTaskManager manager;
    private AsyncTaskService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return future;
        }).when(executor).submit(any(Runnable.class));
        doAnswer(invocation -> {
            AsyncTaskRecord record = invocation.getArgument(0);
            record.setId(1L);
            storedRecord.set(record);
            return 1;
        }).when(taskMapper).insert(any(AsyncTaskRecord.class));
        when(taskMapper.selectByTaskId(any())).thenAnswer(invocation -> storedRecord.get());
        when(taskMapper.selectByIdempotencyKey(any())).thenReturn(null);
        doAnswer(invocation -> {
            AsyncTaskRecord record = storedRecord.get();
            record.setStatus(AsyncTaskStatus.PROCESSING.name());
            record.setLeaseOwner(invocation.getArgument(1));
            record.setAttemptCount(1);
            return 1;
        }).when(taskMapper).claimForExecution(any(), any(), any());
        doReturn(1).when(taskMapper).update(any(), any());

        AsyncTaskHandlerRegistry registry = new AsyncTaskHandlerRegistry();
        manager = new AsyncTaskManager(executor, taskMapper, stepMapper, new ObjectMapper(), registry);
        service = new AsyncTaskService(
                mock(AgentOrchestrator.class), mock(ArxivFetcher.class), mock(PaperMapper.class), manager,
                mock(Planner.class), mock(PlanExecutor.class), ragIndexingService, registry);
    }

    @Test
    void ragTaskShouldPersistCompletedOnlyAfterRealSuccess() {
        when(ragIndexingService.indexPaper(10L)).thenReturn(new RagIndexingResult(10L, true, 3));

        String taskId = service.submitRagIndex(10L);
        AsyncTaskResult<?> result = manager.get(taskId);

        assertThat(result.getStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
        assertThat(result.getResult()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) result.getResult();
        assertThat(payload.get("indexed")).isEqualTo(true);
        assertThat(payload.get("chunkCount")).isEqualTo(3);
    }

    @Test
    void ragTaskShouldPersistFailureInsteadOfFalseSuccess() {
        when(ragIndexingService.indexPaper(11L)).thenThrow(new RagIndexingException(
                RagIndexingException.Reason.ANALYSIS_MISSING, "尚无分析结果"));

        String taskId = service.submitRagIndex(11L);
        AsyncTaskResult<?> result = manager.get(taskId);

        assertThat(result.getStatus()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(result.getResult()).isNull();
        assertThat(result.getError()).contains("尚无分析结果");
    }
}
