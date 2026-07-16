package com.research.assistant.service.workbench;

import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Submits one recoverable async task per persisted workbench run. */
@Service
public class WorkbenchExecutionService {

    public static final String TASK_TYPE = "paper-workbench";

    private final AsyncTaskManager asyncTaskManager;
    private final WorkbenchRunTraceService traceService;
    private final WorkbenchExecutionEngine executionEngine;

    public WorkbenchExecutionService(AsyncTaskManager asyncTaskManager,
                                     AsyncTaskHandlerRegistry handlerRegistry,
                                     WorkbenchRunTraceService traceService,
                                     WorkbenchExecutionEngine executionEngine) {
        this.asyncTaskManager = asyncTaskManager;
        this.traceService = traceService;
        this.executionEngine = executionEngine;
        if (!handlerRegistry.contains(TASK_TYPE)) {
            handlerRegistry.register(TASK_TYPE, context -> {
                Object value = context.arguments().get("runId");
                if (!(value instanceof String runId) || runId.isBlank()) {
                    throw new IllegalArgumentException("workbench task is missing runId");
                }
                try {
                    return executionEngine.execute(runId, context.taskId(), context::stage);
                } catch (AsyncTaskExecutionException error) {
                    if (error.isRetryable() && context.isLastAttempt()) {
                        WorkbenchRunStatus status = traceService.requireTrace(runId).status();
                        if (status != WorkbenchRunStatus.COMPLETED && status != WorkbenchRunStatus.FAILED
                                && status != WorkbenchRunStatus.CANCELLED) {
                            traceService.failRun(runId, error.getFailureCode(), error.getMessage());
                        }
                    }
                    throw error;
                }
            });
        }
    }

    public Submission submit(String runId) {
        WorkbenchRunTrace trace = traceService.requireTrace(runId);
        if (trace.status() == WorkbenchRunStatus.QUEUED || trace.status() == WorkbenchRunStatus.RUNNING
                || trace.status() == WorkbenchRunStatus.COMPLETED) {
            return new Submission(runId, trace.taskId(), trace.status());
        }
        if (trace.status() != WorkbenchRunStatus.PLANNED) {
            throw new IllegalStateException("only a planned workbench run can execute");
        }

        String taskId = UUID.randomUUID().toString();
        traceService.markQueued(runId, taskId);
        try {
            asyncTaskManager.submitRecoverable(
                    taskId,
                    TASK_TYPE,
                    trace.plan().workflow().name(),
                    title(trace.plan().workflow()),
                    Map.of("runId", runId),
                    "workbench-run:" + runId);
            return new Submission(runId, taskId, WorkbenchRunStatus.QUEUED);
        } catch (RuntimeException e) {
            WorkbenchRunStatus status = traceService.requireTrace(runId).status();
            if (status == WorkbenchRunStatus.QUEUED) {
                traceService.failRun(runId, "TASK_SUBMISSION_FAILED", "工作台任务提交失败");
            }
            throw e;
        }
    }

    /** Repairs a trace left active after its recoverable task has already reached a terminal state. */
    public WorkbenchRunTrace reconcile(WorkbenchRunTrace trace) {
        if (trace == null || trace.taskId() == null
                || trace.status() == WorkbenchRunStatus.COMPLETED
                || trace.status() == WorkbenchRunStatus.FAILED
                || trace.status() == WorkbenchRunStatus.CANCELLED) {
            return trace;
        }
        AsyncTaskResult<?> task = asyncTaskManager.get(trace.taskId());
        if (task == null || !task.getStatus().isTerminal()) return trace;
        if (task.getStatus() == AsyncTaskStatus.CANCELLED) {
            traceService.cancelRun(trace.runId(), "任务已取消");
        } else if (task.getStatus() == AsyncTaskStatus.COMPLETED) {
            traceService.failRun(trace.runId(), "TASK_TRACE_INCONSISTENT", "任务已完成但运行结果缺失，请重新执行");
        } else {
            traceService.failRun(trace.runId(), "TASK_TERMINATED", safeTaskError(task));
        }
        return traceService.requireTrace(trace.runId());
    }

    private String safeTaskError(AsyncTaskResult<?> task) {
        return task.getError() == null || task.getError().isBlank() ? "论文助手任务执行失败" : task.getError();
    }

    private String title(WorkbenchPlan.Workflow workflow) {
        return switch (workflow) {
            case SELECTION_QA -> "选区问答";
            case PAPER_ANALYSIS -> "论文全文分析";
            case ANNOTATION_SUGGESTION -> "批注建议";
            case PAPER_COMPARISON -> "论文对比";
            case RESEARCH_GAP -> "研究 Gap";
        };
    }

    public record Submission(String runId, String taskId, WorkbenchRunStatus status) {
    }
}
