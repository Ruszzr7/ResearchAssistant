package com.research.assistant.service.async;

import com.research.assistant.service.ai.workflow.WorkflowStepView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步任务结果封装。
 *
 * @param <T> 任务成功后的结果类型
 */
public class AsyncTaskResult<T> {

    private final String taskId;
    private final AsyncTaskStatus status;
    private final String stageText;
    private final T result;
    private final String error;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final String workflowType;
    private final List<WorkflowStepView> steps;
    private final String title;

    AsyncTaskResult(String taskId, AsyncTaskStatus status, String stageText,
                    T result, String error, LocalDateTime createdAt, LocalDateTime updatedAt,
                    String workflowType, List<WorkflowStepView> steps, String title) {
        this.taskId = taskId;
        this.status = status;
        this.stageText = stageText;
        this.result = result;
        this.error = error;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.workflowType = workflowType;
        this.steps = steps;
        this.title = title;
    }

    public static <T> AsyncTaskResult<T> pending(String taskId, String stageText) {
        return pending(taskId, stageText, null, null, null);
    }

    public static <T> AsyncTaskResult<T> pending(String taskId, String stageText,
                                                 String workflowType, List<WorkflowStepView> steps) {
        return pending(taskId, stageText, workflowType, steps, null);
    }

    public static <T> AsyncTaskResult<T> pending(String taskId, String stageText,
                                                 String workflowType, List<WorkflowStepView> steps,
                                                 String title) {
        LocalDateTime now = LocalDateTime.now();
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.PENDING, stageText, null, null, now, now,
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> processing(String stageText) {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.PROCESSING, stageText, null, null, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> completed(T result) {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.COMPLETED, null, result, null, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> failed(String error) {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.FAILED, null, null, error, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> pendingUser(T result) {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.PENDING_USER, null, result, null, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> cancelled() {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.CANCELLED, null, null, null, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public AsyncTaskResult<T> expired(String error) {
        return new AsyncTaskResult<>(taskId, AsyncTaskStatus.EXPIRED, null, null, error, createdAt, LocalDateTime.now(),
                workflowType, steps, title);
    }

    public String getTaskId() { return taskId; }
    public AsyncTaskStatus getStatus() { return status; }

    public String getStageText() {
        if (stageText != null) {
            return stageText;
        }
        return switch (status) {
            case COMPLETED -> "完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
            case EXPIRED -> "已过期";
            default -> "";
        };
    }

    public T getResult() { return result; }
    public String getError() { return error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getWorkflowType() { return workflowType; }
    public List<WorkflowStepView> getSteps() { return steps; }
    public String getTitle() { return title; }
}
