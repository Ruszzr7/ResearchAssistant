package com.research.assistant.service.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.entity.WorkflowStepRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.service.ai.workflow.WorkflowStepView;
import com.research.assistant.service.observability.ResearchMetrics;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean markOrphanedOnStartup;
    private final Duration pendingUserTtl;
    private final Duration executionTimeout;
    private final ResearchMetrics metrics;
    private final Map<String, TaskHolder> tasks = new ConcurrentHashMap<>();

    @Autowired
    public AsyncTaskManager(@Qualifier("taskExecutor") AsyncTaskExecutor taskExecutor,
                            AsyncTaskRecordMapper taskRecordMapper,
                            WorkflowStepMapper workflowStepMapper,
                            ObjectMapper objectMapper,
                            @Value("${app.async.mark-orphaned-on-startup:true}") boolean markOrphanedOnStartup,
                            @Value("${app.async.pending-user-ttl:24h}") Duration pendingUserTtl,
                            @Value("${app.async.execution-timeout:30m}") Duration executionTimeout,
                            ResearchMetrics metrics) {
        this.taskExecutor = taskExecutor;
        this.taskRecordMapper = taskRecordMapper;
        this.workflowStepMapper = workflowStepMapper;
        this.objectMapper = objectMapper;
        this.markOrphanedOnStartup = markOrphanedOnStartup;
        this.pendingUserTtl = pendingUserTtl;
        this.executionTimeout = executionTimeout;
        this.metrics = metrics;
    }

    /**
     * 保留无配置参数的构造器，便于纯单元测试和已有调用方直接实例化。
     * 生产环境由 Spring 注入带配置的构造器。
     */
    public AsyncTaskManager(AsyncTaskExecutor taskExecutor,
                            AsyncTaskRecordMapper taskRecordMapper,
                            WorkflowStepMapper workflowStepMapper,
                            ObjectMapper objectMapper) {
        this(taskExecutor, taskRecordMapper, workflowStepMapper, objectMapper, true,
                Duration.ofHours(24), Duration.ofMinutes(30), new ResearchMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 启动时把上次未完成的孤儿任务标记为失败。
     */
    @PostConstruct
    public void markOrphanedTasksAsFailed() {
        if (!markOrphanedOnStartup) {
            log.info("已跳过启动时孤儿任务恢复（配置 app.async.mark-orphaned-on-startup=false）");
            return;
        }
        try {
            UpdateWrapper<AsyncTaskRecord> wrapper = new UpdateWrapper<>();
            wrapper.in("status",
                            java.util.Arrays.asList(AsyncTaskStatus.PENDING.name(), AsyncTaskStatus.PROCESSING.name()))
                    .set("status", AsyncTaskStatus.FAILED.name())
                    .set("stage_text", "失败")
                    .set("result_json", null)
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
        TaskHolder holder;
        synchronized (tasks) {
            TaskHolder existing = tasks.get(taskId);
            if (existing != null) {
                synchronized (existing) {
                    if (!existing.result.getStatus().canRestart()) {
                        throw new IllegalStateException("任务正在执行或已完成，不能重复提交: " + taskId);
                    }
                    finishMetrics(existing, "resumed");
                }
            }
            Long recordId = insertRecord(initial, contextJson);
            holder = new TaskHolder(initial, null, recordId, metrics.startTimer(), workflowType);
            tasks.put(taskId, holder);
        }
        metrics.taskSubmitted(workflowType);

        Consumer<String> setStage = stage -> updateStage(taskId, stage);
        try {
            Future<?> future = taskExecutor.submit(() -> {
                try {
                    if (!updateStage(taskId, "正在处理…")) {
                        return;
                    }
                    T result = task.apply(taskId, setStage);
                    TaskHolder current = tasks.get(taskId);
                    if (current != null && current.result.getStatus() == AsyncTaskStatus.PENDING_USER) {
                        log.info("event=async_task_pending_user taskId={} type={}", taskId, taskType(workflowType));
                    } else if (updateResult(taskId, initial.completed(result))) {
                        log.info("event=async_task_completed taskId={} type={}", taskId, taskType(workflowType));
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        if (updateResult(taskId, initial.cancelled())) {
                            log.info("event=async_task_cancelled taskId={} type={}", taskId, taskType(workflowType));
                        }
                    } else {
                        String error = safeError(e);
                        if (updateResult(taskId, initial.failed(error))) {
                            log.error("event=async_task_failed taskId={} type={} errorType={} message={}",
                                    taskId, taskType(workflowType), e.getClass().getSimpleName(), error);
                        }
                    }
                }
            });
            synchronized (holder) {
                if (holder.result.getStatus() == AsyncTaskStatus.PENDING
                        || holder.result.getStatus() == AsyncTaskStatus.PROCESSING) {
                    holder.future = future;
                }
            }
        } catch (RuntimeException e) {
            String error = "任务执行器拒绝提交: " + safeError(e);
            updateResult(taskId, initial.failed(error));
            log.error("event=async_task_rejected taskId={} type={} errorType={} message={}",
                    taskId, taskType(workflowType), e.getClass().getSimpleName(), safeError(e));
        }
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
        synchronized (holder) {
            if (!holder.result.getStatus().canTransitionTo(AsyncTaskStatus.PENDING_USER)) {
                return;
            }
            @SuppressWarnings("unchecked")
            AsyncTaskResult<Object> updated = ((AsyncTaskResult<Object>) holder.result).pendingUser(partialResult);
            holder.result = updated;
            holder.future = null;
            updateRecord(holder.recordId, updated);
        }
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
        tasks.put(taskId, new TaskHolder(result, null, record.getId(), metrics.startTimer(), record.getWorkflowType()));
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
        if (holder != null) {
            Future<?> future;
            synchronized (holder) {
                if (!holder.result.getStatus().canTransitionTo(AsyncTaskStatus.CANCELLED)) {
                    return false;
                }
                AsyncTaskResult<?> cancelled = holder.result.cancelled();
                holder.result = cancelled;
                future = holder.future;
                holder.future = null;
                updateRecord(holder.recordId, cancelled);
                finishMetrics(holder, "cancelled");
            }
            if (future != null) {
                future.cancel(true);
            }
            log.info("event=async_task_cancelled taskId={} type={}", taskId, taskType(holder.taskType));
            return true;
        }

        // 内存未命中：尝试在数据库中取消非终态任务
        UpdateWrapper<AsyncTaskRecord> wrapper = new UpdateWrapper<>();
        wrapper.eq("task_id", taskId)
                .in("status", java.util.Arrays.asList(
                        AsyncTaskStatus.PENDING.name(), AsyncTaskStatus.PROCESSING.name(),
                        AsyncTaskStatus.PENDING_USER.name()))
                .set("status", AsyncTaskStatus.CANCELLED.name())
                .set("stage_text", "已取消")
                .set("result_json", null)
                .set("error", null)
                .set("updated_at", LocalDateTime.now());
        return taskRecordMapper.update(null, wrapper) > 0;
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
    public boolean updateStage(String taskId, String stageText) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return false;
        }
        synchronized (holder) {
            AsyncTaskStatus status = holder.result.getStatus();
            if (status != AsyncTaskStatus.PENDING && status != AsyncTaskStatus.PROCESSING) {
                return false;
            }
            if (java.util.Objects.equals(holder.result.getStageText(), stageText)
                    && status == AsyncTaskStatus.PROCESSING) {
                return true;
            }
            AsyncTaskResult<?> updated = holder.result.processing(stageText);
            holder.result = updated;
            updateRecord(holder.recordId, updated);
            return true;
        }
    }

    private boolean updateResult(String taskId, AsyncTaskResult<?> result) {
        TaskHolder holder = tasks.get(taskId);
        if (holder == null) {
            return false;
        }
        synchronized (holder) {
            if (!holder.result.getStatus().canTransitionTo(result.getStatus())) {
                return false;
            }
            holder.result = result;
            holder.future = null; // 释放 Future 及其闭包引用
            updateRecord(holder.recordId, result);
            finishMetrics(holder, result.getStatus().name().toLowerCase(java.util.Locale.ROOT));
            return true;
        }
    }

    private Long insertRecord(AsyncTaskResult<?> result, String contextJson) {
        AsyncTaskRecord existing = taskRecordMapper.selectByTaskId(result.getTaskId());
        if (existing != null) {
            AsyncTaskStatus existingStatus = parseStatus(existing.getStatus());
            if (!existingStatus.canRestart()) {
                throw new IllegalStateException("任务当前状态不允许重新提交: " + existing.getStatus());
            }
            // 工作流重试等场景：复用已有记录，重置为 PENDING
            existing.setStatus(result.getStatus().name());
            existing.setStageText(result.getStageText());
            existing.setResultJson(null);
            existing.setError(null);
            existing.setWorkflowType(result.getWorkflowType());
            existing.setContextJson(contextJson);
            existing.setTitle(result.getTitle());
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
        AsyncTaskStatus status = parseStatus(record.getStatus());
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
        return parseStatus(status).isTerminal();
    }

    private AsyncTaskStatus parseStatus(String status) {
        try {
            return AsyncTaskStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AsyncTaskStatus.FAILED;
        }
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String sanitized = message
                .replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)sk-[a-z0-9_-]{8,}", "[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }

    private String taskType(String workflowType) {
        return workflowType == null || workflowType.isBlank() ? "general" : workflowType;
    }

    private void finishMetrics(TaskHolder holder, String outcome) {
        if (!holder.metricsFinished) {
            holder.metricsFinished = true;
            metrics.taskFinished(holder.taskType, outcome, holder.startedAtNanos);
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

    /** 将超过确认期限的 PENDING_USER 任务结算为 EXPIRED。 */
    @Scheduled(
            fixedDelayString = "${app.async.pending-user-expiry-scan-ms:60000}",
            initialDelayString = "${app.async.pending-user-expiry-scan-ms:60000}")
    public void expirePendingUserTasks() {
        if (pendingUserTtl == null || pendingUserTtl.isZero() || pendingUserTtl.isNegative()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(pendingUserTtl);
        int inMemoryExpired = 0;
        for (Map.Entry<String, TaskHolder> entry : tasks.entrySet()) {
            TaskHolder holder = entry.getValue();
            synchronized (holder) {
                if (holder.result.getStatus() != AsyncTaskStatus.PENDING_USER
                        || !holder.result.getUpdatedAt().isBefore(cutoff)) {
                    continue;
                }
                AsyncTaskResult<?> expired = holder.result.expired("等待用户确认超时，请重新提交任务");
                holder.result = expired;
                holder.future = null;
                updateRecord(holder.recordId, expired);
                finishMetrics(holder, "expired");
                inMemoryExpired++;
            }
        }

        UpdateWrapper<AsyncTaskRecord> wrapper = new UpdateWrapper<>();
        wrapper.eq("status", AsyncTaskStatus.PENDING_USER.name())
                .lt("updated_at", cutoff)
                .set("status", AsyncTaskStatus.EXPIRED.name())
                .set("stage_text", "已过期")
                .set("result_json", null)
                .set("error", "等待用户确认超时，请重新提交任务")
                .set("updated_at", LocalDateTime.now());
        int databaseExpired = taskRecordMapper.update(null, wrapper);
        int total = inMemoryExpired + databaseExpired;
        if (total > 0) {
            log.info("event=async_task_expired count={} ttlSeconds={}", total, pendingUserTtl.toSeconds());
        }
    }

    /** 将超过执行时限的排队/运行任务结算为 FAILED，并尝试中断工作线程。 */
    @Scheduled(
            fixedDelayString = "${app.async.execution-timeout-scan-ms:60000}",
            initialDelayString = "${app.async.execution-timeout-scan-ms:60000}")
    public void timeoutActiveTasks() {
        if (executionTimeout == null || executionTimeout.isZero() || executionTimeout.isNegative()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(executionTimeout);
        int inMemoryTimedOut = 0;
        for (Map.Entry<String, TaskHolder> entry : tasks.entrySet()) {
            TaskHolder holder = entry.getValue();
            Future<?> future;
            synchronized (holder) {
                AsyncTaskStatus status = holder.result.getStatus();
                LocalDateTime createdAt = holder.result.getCreatedAt();
                if ((status != AsyncTaskStatus.PENDING && status != AsyncTaskStatus.PROCESSING)
                        || createdAt == null || !createdAt.isBefore(cutoff)) {
                    continue;
                }
                AsyncTaskResult<?> failed = holder.result.failed("任务执行超时，请重试");
                holder.result = failed;
                future = holder.future;
                holder.future = null;
                updateRecord(holder.recordId, failed);
                finishMetrics(holder, "timeout");
                inMemoryTimedOut++;
            }
            if (future != null) {
                future.cancel(true);
            }
        }

        UpdateWrapper<AsyncTaskRecord> wrapper = new UpdateWrapper<>();
        wrapper.in("status", java.util.Arrays.asList(
                        AsyncTaskStatus.PENDING.name(), AsyncTaskStatus.PROCESSING.name()))
                .lt("created_at", cutoff)
                .set("status", AsyncTaskStatus.FAILED.name())
                .set("stage_text", "失败")
                .set("result_json", null)
                .set("error", "任务执行超时，请重试")
                .set("updated_at", LocalDateTime.now());
        int databaseTimedOut = taskRecordMapper.update(null, wrapper);
        int total = inMemoryTimedOut + databaseTimedOut;
        if (total > 0) {
            log.warn("event=async_task_timeout count={} timeoutSeconds={}", total, executionTimeout.toSeconds());
        }
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
                                    AsyncTaskStatus.CANCELLED.name(),
                                    AsyncTaskStatus.EXPIRED.name()))
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
        long startedAtNanos;
        String taskType;
        boolean metricsFinished;

        TaskHolder(AsyncTaskResult<?> result, Future<?> future, Long recordId,
                   long startedAtNanos, String taskType) {
            this.result = result;
            this.future = future;
            this.recordId = recordId;
            this.startedAtNanos = startedAtNanos;
            this.taskType = taskType;
        }
    }
}
