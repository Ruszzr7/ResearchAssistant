package com.research.assistant.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.entity.WorkflowStepRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.service.ai.workflow.WorkflowStepView;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 异步任务管理器 —— 内存 + MySQL 双写，支持跨重启查询。
 *
 * <p>任务状态实时同步到 {@code async_task} 表；后端重启后，
 * 内存中的 {@code tasks} 为空，可通过 {@link #get(String)} 回查数据库恢复结果。
 * 重启前未完成的 PENDING/PROCESSING 任务会在启动时被标记为 FAILED，避免用户无限等待。</p>
 */
@Service
public class AsyncTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskManager.class);

    /** 内存中保留 30 分钟 */
    private static final long RETENTION_MINUTES = 30;

    /** DB 中保留 7 天 */
    private static final Duration DB_RETENTION = Duration.ofDays(7);

    private final AsyncTaskExecutor taskExecutor;
    private final AsyncTaskRecordMapper taskRecordMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, TaskHolder> tasks = new ConcurrentHashMap<>();

    public AsyncTaskManager(AsyncTaskExecutor taskExecutor,
                            AsyncTaskRecordMapper taskRecordMapper,
                            WorkflowStepMapper workflowStepMapper,
                            ObjectMapper objectMapper) {
        this.taskExecutor = taskExecutor;
        this.taskRecordMapper = taskRecordMapper;
        this.workflowStepMapper = workflowStepMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时把上次未完成的孤儿任务标记为失败。
     */
    @PostConstruct
    public void markOrphanedTasksAsFailed() {
        try {
            UpdateWrapper<AsyncTaskRecord> wrapper = new UpdateWrapper<>();
            wrapper.in("status",
                            java.util.Arrays.asList(AsyncTaskStatus.PENDING.name(), AsyncTaskStatus.PROCESSING.name()))
                    .set("status", AsyncTaskStatus.FAILED.name())
                    .set("error", "服务重启，任务中断，请重新提交")
                    .set("updated_at", LocalDateTime.now());
            int updated = taskRecordMapper.update(null, wrapper);
            if (updated > 0) {
                log.info("启动时将 {} 个未完成异步任务标记为 FAILED", updated);
            }
        } catch (Exception e) {
            log.warn("标记孤儿异步任务失败: {}", e.getMessage());
        }
    }

    /**
     * 提交一个异步任务。
     *
     * @param task 任务执行逻辑，接收一个 {@code setStage} 回调用于更新前端可见的阶段文案
     * @param <T>  结果类型
     * @return 任务 ID
     */
    public <T> String submit(Function<Consumer<String>, T> task) {
        return submit(null, task);
    }

    /**
     * 提交一个异步任务（带标题）。
     */
    public <T> String submit(String title, Function<Consumer<String>, T> task) {
        return submit(UUID.randomUUID().toString(), null, null, title, (id, setStage) -> task.apply(setStage));
    }

    /**
     * 提交一个工作流异步任务。
     *
     * @param workflowType 工作流模板 key
     * @param contextJson  工作流启动上下文 JSON
     * @param title        任务展示标题
     * @param task         任务执行逻辑，接收 taskId 与 setStage 回调
     * @param <T>          结果类型
     * @return 任务 ID
     */
    public <T> String submit(String workflowType, String contextJson, String title,
                             BiFunction<String, Consumer<String>, T> task) {
        return submit(UUID.randomUUID().toString(), workflowType, contextJson, title, task);
    }

    /**
     * 提交一个异步任务（指定 taskId，主要用于工作流重试）。
     */
    public <T> String submit(String taskId, String workflowType, String contextJson, String title,
                             BiFunction<String, Consumer<String>, T> task) {
        AsyncTaskResult<T> initial = AsyncTaskResult.pending(taskId, "排队中…", workflowType, null, title);
        Long recordId = insertRecord(initial, contextJson);
        tasks.put(taskId, new TaskHolder(initial, null, recordId));

        Consumer<String> setStage = stage -> updateStage(taskId, stage);
        Future<?> future = taskExecutor.submit(() -> {
            try {
                updateStage(taskId, "正在处理…");
                T result = task.apply(taskId, setStage);
                TaskHolder current = tasks.get(taskId);
                if (current != null && current.result.getStatus() == AsyncTaskStatus.PENDING_USER) {
                    // 工作流已设置为人机确认状态，不再覆盖
                    log.info("异步任务进入等待用户确认状态: taskId={}", taskId);
                } else {
                    updateResult(taskId, initial.completed(result));
                    log.info("异步任务完成: taskId={}", taskId);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    updateResult(taskId, initial.cancelled());
                    log.info("异步任务被取消/中断: taskId={}", taskId);
                } else {
                    updateResult(taskId, initial.failed(e.getMessage()));
                    log.error("异步任务失败: taskId={}", taskId, e);
                }
            }
        });

        tasks.get(taskId).future = future;
        return taskId;
    }

    /**
     * 将任务置为等待用户确认状态（用于人机确认工作流）。
     */
    public void setPendingUser(String taskId, Object partialResult) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        AsyncTaskResult<Object> updated = ((AsyncTaskResult<Object>) holder.result).pendingUser(partialResult);
        holder.result = updated;
        updateRecord(holder.recordId, updated);
    }

    /**
     * 查询任务结果。优先读内存，内存未命中则回查数据库。
     */
    @SuppressWarnings("unchecked")
    public <T> AsyncTaskResult<T> get(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder != null) {
            return (AsyncTaskResult<T>) holder.result;
        }
        AsyncTaskRecord record = taskRecordMapper.selectByTaskId(taskId);
        if (record == null) {
            return null;
        }
        AsyncTaskResult<?> result = toResult(record);
        // 缓存到内存，减少后续轮询查 DB
        tasks.put(taskId, new TaskHolder(result, null, record.getId()));
        return (AsyncTaskResult<T>) result;
    }

    /**
     * 查询最近的任务列表。
     */
    public List<AsyncTaskResult<?>> listRecent(int limit) {
        List<AsyncTaskRecord> records = taskRecordMapper.selectRecent(limit);
        return records.stream()
                .map(this::toResult)
                .collect(Collectors.toList());
    }

    /**
     * 取消任务。
     *
     * @return true 表示成功发起取消
     */
    public boolean cancel(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder != null && !holder.result.getStatus().isTerminal()) {
            if (holder.future != null) {
                holder.future.cancel(true);
            }
            AsyncTaskResult<?> cancelled = holder.result.cancelled();
            holder.result = cancelled;
            holder.future = null;
            updateRecord(holder.recordId, cancelled);
            return true;
        }

        // 内存未命中：尝试在数据库中取消非终态任务
        AsyncTaskRecord record = taskRecordMapper.selectByTaskId(taskId);
        if (record != null && !isTerminal(record.getStatus())) {
            record.setStatus(AsyncTaskStatus.CANCELLED.name());
            record.setUpdatedAt(LocalDateTime.now());
            taskRecordMapper.updateById(record);
            return true;
        }
        return false;
    }

    /**
     * 删除任务（仅允许终态）。
     *
     * @return true 表示删除成功
     */
    public boolean delete(String taskId) {
        TaskHolder holder = tasks.get(taskId);
        if (holder != null) {
            if (!holder.result.getStatus().isTerminal()) {
                return false;
            }
            tasks.remove(taskId);
        }
        AsyncTaskRecord record = taskRecordMapper.selectByTaskId(taskId);
        if (record == null) {
            return holder != null;
        }
        if (!isTerminal(record.getStatus())) {
            return false;
        }
        try {
            workflowStepMapper.deleteByTaskId(taskId);
        } catch (Exception e) {
            log.warn("删除任务步骤失败 taskId={}: {}", taskId, e.getMessage());
        }
        taskRecordMapper.deleteById(record.getId());
        return true;
    }
    public void updateStage(String taskId, String stageText) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return;
        }
        AsyncTaskResult<?> updated = holder.result.processing(stageText);
        holder.result = updated;
        updateRecord(holder.recordId, updated);
    }

    private void updateResult(String taskId, AsyncTaskResult<?> result) {
        TaskHolder holder = tasks.get(taskId);
        if (holder != null) {
            holder.result = result;
            holder.future = null; // 释放 Future 及其闭包引用
            updateRecord(holder.recordId, result);
        }
    }

    private Long insertRecord(AsyncTaskResult<?> result, String contextJson) {
        AsyncTaskRecord existing = taskRecordMapper.selectByTaskId(result.getTaskId());
        if (existing != null) {
            // 工作流重试等场景：复用已有记录，重置为 PENDING
            existing.setStatus(result.getStatus().name());
            existing.setStageText(result.getStageText());
            existing.setResultJson(null);
            existing.setError(null);
            existing.setWorkflowType(result.getWorkflowType());
            existing.setContextJson(contextJson);
            existing.setUpdatedAt(LocalDateTime.now());
            taskRecordMapper.updateById(existing);
            return existing.getId();
        }
        AsyncTaskRecord record = newRecord(result);
        record.setContextJson(contextJson);
        taskRecordMapper.insert(record);
        return record.getId();
    }

    private void updateRecord(Long recordId, AsyncTaskResult<?> result) {
        if (recordId == null) {
            return;
        }
        AsyncTaskRecord record = newRecord(result);
        record.setId(recordId);
        taskRecordMapper.updateById(record);
    }

    private AsyncTaskRecord newRecord(AsyncTaskResult<?> result) {
        AsyncTaskRecord record = new AsyncTaskRecord();
        record.setTaskId(result.getTaskId());
        record.setWorkflowType(result.getWorkflowType());
        record.setTitle(result.getTitle());
        record.setStatus(result.getStatus().name());
        record.setStageText(result.getStageText());
        record.setResultJson(toJson(result.getResult()));
        record.setError(result.getError());
        record.setCreatedAt(result.getCreatedAt());
        record.setUpdatedAt(result.getUpdatedAt());
        return record;
    }

    private AsyncTaskResult<?> toResult(AsyncTaskRecord record) {
        AsyncTaskStatus status;
        try {
            status = AsyncTaskStatus.valueOf(record.getStatus());
        } catch (IllegalArgumentException e) {
            status = AsyncTaskStatus.FAILED;
        }
        List<WorkflowStepView> steps = null;
        if (record.getWorkflowType() != null && workflowStepMapper != null) {
            steps = loadSteps(record.getTaskId());
        }
        return new AsyncTaskResult<>(
                record.getTaskId(),
                status,
                record.getStageText(),
                fromJson(record.getResultJson()),
                record.getError(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.getWorkflowType(),
                steps,
                record.getTitle()
        );
    }

    private List<WorkflowStepView> loadSteps(String taskId) {
        try {
            List<WorkflowStepRecord> records = workflowStepMapper.findByTaskId(taskId);
            return records.stream()
                    .map(r -> new WorkflowStepView(
                            r.getStepIndex(),
                            r.getStepName(),
                            r.getSkillName(),
                            r.getStatus(),
                            r.getError(),
                            r.getStartedAt(),
                            r.getCompletedAt()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("加载工作流步骤失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("任务结果序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("任务结果反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTerminal(String status) {
        try {
            return AsyncTaskStatus.valueOf(status).isTerminal();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 定时清理内存中已完成且过期的任务。
     */
    @Scheduled(fixedDelay = 300_000) // 每 5 分钟
    public void cleanupOldTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(RETENTION_MINUTES);
        tasks.entrySet().removeIf(entry -> {
            AsyncTaskResult<?> result = entry.getValue().result;
            return result.getStatus().isTerminal() && result.getUpdatedAt().isBefore(cutoff);
        });
    }

    /**
     * 定时清理 DB 中 7 天前的终态任务。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupOldDbTasks() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(DB_RETENTION);
            QueryWrapper<AsyncTaskRecord> wrapper = new QueryWrapper<>();
            wrapper.in("status",
                            java.util.Arrays.asList(
                                    AsyncTaskStatus.COMPLETED.name(),
                                    AsyncTaskStatus.FAILED.name(),
                                    AsyncTaskStatus.CANCELLED.name()))
                    .lt("updated_at", cutoff);
            int deleted = taskRecordMapper.delete(wrapper);
            if (deleted > 0) {
                log.info("清理 {} 天前异步任务记录 {} 条", DB_RETENTION.toDays(), deleted);
            }
        } catch (Exception e) {
            log.warn("清理过期异步任务记录失败: {}", e.getMessage());
        }
    }

    private static class TaskHolder {
        AsyncTaskResult<?> result;
        Future<?> future;
        Long recordId;

        TaskHolder(AsyncTaskResult<?> result, Future<?> future, Long recordId) {
            this.result = result;
            this.future = future;
            this.recordId = recordId;
        }
    }
}
