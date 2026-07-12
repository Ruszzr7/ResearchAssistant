package com.research.assistant.service.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.WorkflowStepRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.ai.skill.SkillRegistry;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskStatus;
import com.research.assistant.service.async.AsyncTaskExecutionContext;
import com.research.assistant.service.async.AsyncTaskHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工作流引擎 —— 顺序执行预定义 Workflow，持久化每一步状态，支持重试与人机确认暂停。
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final AsyncTaskManager asyncTaskManager;
    private final WorkflowRegistry workflowRegistry;
    private final WorkflowStepMapper workflowStepMapper;
    private final AsyncTaskRecordMapper asyncTaskRecordMapper;
    private final WorkflowArgumentResolver argumentResolver;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final AsyncTaskHandlerRegistry handlerRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowEngine(AsyncTaskManager asyncTaskManager,
                          WorkflowRegistry workflowRegistry,
                          WorkflowStepMapper workflowStepMapper,
                          AsyncTaskRecordMapper asyncTaskRecordMapper,
                          WorkflowArgumentResolver argumentResolver,
                          SkillRegistry skillRegistry,
                          ObjectMapper objectMapper,
                          AsyncTaskHandlerRegistry handlerRegistry) {
        this.asyncTaskManager = asyncTaskManager;
        this.workflowRegistry = workflowRegistry;
        this.workflowStepMapper = workflowStepMapper;
        this.asyncTaskRecordMapper = asyncTaskRecordMapper;
        this.argumentResolver = argumentResolver;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.handlerRegistry = handlerRegistry;
        registerRecoverableHandlers();
    }

    public WorkflowEngine(AsyncTaskManager asyncTaskManager,
                          WorkflowRegistry workflowRegistry,
                          WorkflowStepMapper workflowStepMapper,
                          AsyncTaskRecordMapper asyncTaskRecordMapper,
                          WorkflowArgumentResolver argumentResolver,
                          SkillRegistry skillRegistry,
                          ObjectMapper objectMapper) {
        this(asyncTaskManager, workflowRegistry, workflowStepMapper, asyncTaskRecordMapper,
                argumentResolver, skillRegistry, objectMapper, new AsyncTaskHandlerRegistry());
    }

    private void registerRecoverableHandlers() {
        for (WorkflowDefinition definition : workflowRegistry.all()) {
            String taskType = recoverableType(definition.key());
            if (!handlerRegistry.contains(taskType)) {
                handlerRegistry.register(taskType, context -> executePersisted(context, definition));
            }
        }
    }

    private String recoverableType(String workflowKey) {
        return "workflow:" + workflowKey;
    }

    /**
     * 提交工作流任务。
     */
    public String submit(String workflowKey, Map<String, Object> context) {
        WorkflowDefinition def = workflowRegistry.get(workflowKey);
        if (def == null) {
            throw new WorkflowException("未知工作流: " + workflowKey);
        }
        String contextJson = toJson(context);
        return asyncTaskManager.submit(workflowKey, contextJson, def.name(), (taskId, setStage) -> {
            setStage.accept("正在初始化工作流…");
            createStepRecords(taskId, def);
            return runSteps(taskId, def, context, 0, Map.of(), setStage);
        });
    }

    /** 生产入口：工作流上下文和恢复位置均持久化，可由调度器在重启后继续。 */
    public String submitRecoverable(String workflowKey, Map<String, Object> context) {
        WorkflowDefinition def = workflowRegistry.get(workflowKey);
        if (def == null) {
            throw new WorkflowException("未知工作流: " + workflowKey);
        }
        return asyncTaskManager.submitRecoverable(
                recoverableType(workflowKey), workflowKey, def.name(),
                workflowPayload(context, 0, Map.of()), null);
    }

    /**
     * 查询工作流任务结果。
     */
    public AsyncTaskResult<?> get(String taskId) {
        return asyncTaskManager.get(taskId);
    }

    /**
     * 从失败点重试工作流。
     */
    public String retry(String taskId) {
        AsyncTaskResult<?> result = asyncTaskManager.get(taskId);
        if (result == null) {
            throw new WorkflowException("任务不存在: " + taskId);
        }
        if (result.getStatus() != AsyncTaskStatus.FAILED
                && result.getStatus() != AsyncTaskStatus.CANCELLED
                && result.getStatus() != AsyncTaskStatus.EXPIRED) {
            throw new WorkflowException("只有失败、取消或过期的任务可以重试: " + taskId);
        }

        WorkflowDefinition def = workflowRegistry.get(result.getWorkflowType());
        if (def == null) {
            throw new WorkflowException("未知工作流类型，无法重试: " + result.getWorkflowType());
        }

        List<WorkflowStepRecord> stepRecords = workflowStepMapper.findByTaskId(taskId);
        int fromIndex = firstNonCompletedIndex(stepRecords);

        // 重置从失败点开始及之后的步骤
        for (int i = fromIndex; i < stepRecords.size(); i++) {
            WorkflowStepRecord r = stepRecords.get(i);
            r.setStatus(AsyncTaskStatus.PENDING.name());
            r.setInputJson(null);
            r.setOutputJson(null);
            r.setError(null);
            r.setStartedAt(null);
            r.setCompletedAt(null);
            r.setUpdatedAt(LocalDateTime.now());
            workflowStepMapper.updateById(r);
        }

        Map<String, Object> context = loadContext(taskId);
        return asyncTaskManager.submit(taskId, result.getWorkflowType(), contextJson(taskId), result.getTitle(),
                (workflowId, stageUpdater) -> runSteps(workflowId, def, context, fromIndex, Map.of(), stageUpdater));
    }

    public String retryRecoverable(String taskId) {
        AsyncTaskResult<?> result = asyncTaskManager.get(taskId);
        if (result == null) {
            throw new WorkflowException("任务不存在: " + taskId);
        }
        if (result.getStatus() != AsyncTaskStatus.FAILED
                && result.getStatus() != AsyncTaskStatus.CANCELLED
                && result.getStatus() != AsyncTaskStatus.EXPIRED
                && result.getStatus() != AsyncTaskStatus.DEAD_LETTER) {
            throw new WorkflowException("只有失败、取消、过期或死信任务可以重试: " + taskId);
        }
        WorkflowDefinition def = workflowRegistry.get(result.getWorkflowType());
        if (def == null) {
            throw new WorkflowException("未知工作流类型，无法重试: " + result.getWorkflowType());
        }
        List<WorkflowStepRecord> stepRecords = workflowStepMapper.findByTaskId(taskId);
        int fromIndex = firstNonCompletedIndex(stepRecords);
        resetSteps(stepRecords, fromIndex);
        Map<String, Object> context = loadContext(taskId);
        return asyncTaskManager.submitRecoverable(taskId, recoverableType(def.key()), def.key(), result.getTitle(),
                workflowPayload(context, fromIndex, Map.of()), null);
    }

    /**
     * 用户确认后继续执行工作流。
     */
    public String confirm(String taskId, Map<String, Object> userInput) {
        AsyncTaskResult<?> result = asyncTaskManager.get(taskId);
        if (result == null) {
            throw new WorkflowException("任务不存在: " + taskId);
        }
        if (result.getStatus() != AsyncTaskStatus.PENDING_USER) {
            throw new WorkflowException("任务不处于待确认状态: " + taskId);
        }

        WorkflowDefinition def = workflowRegistry.get(result.getWorkflowType());
        if (def == null) {
            throw new WorkflowException("未知工作流类型，无法继续: " + result.getWorkflowType());
        }

        List<WorkflowStepRecord> stepRecords = workflowStepMapper.findByTaskId(taskId);
        int fromIndex = firstNonCompletedIndex(stepRecords);

        Map<String, Object> baseContext = loadContext(taskId);
        final Map<String, Object> mergedContext;
        if (userInput != null) {
            mergedContext = new LinkedHashMap<>(baseContext);
            mergedContext.putAll(userInput);
        } else {
            mergedContext = new LinkedHashMap<>(baseContext);
        }

        return asyncTaskManager.submit(taskId, result.getWorkflowType(), contextJson(taskId), result.getTitle(),
                (workflowId, stageUpdater) -> runSteps(workflowId, def, mergedContext, fromIndex, userInput, stageUpdater));
    }

    public String confirmRecoverable(String taskId, Map<String, Object> userInput) {
        AsyncTaskResult<?> result = asyncTaskManager.get(taskId);
        if (result == null || result.getStatus() != AsyncTaskStatus.PENDING_USER) {
            throw new WorkflowException("任务不处于待确认状态: " + taskId);
        }
        WorkflowDefinition def = workflowRegistry.get(result.getWorkflowType());
        if (def == null) {
            throw new WorkflowException("未知工作流类型，无法继续: " + result.getWorkflowType());
        }
        List<WorkflowStepRecord> stepRecords = workflowStepMapper.findByTaskId(taskId);
        int fromIndex = firstNonCompletedIndex(stepRecords);
        Map<String, Object> baseContext = loadContext(taskId);
        Map<String, Object> merged = new LinkedHashMap<>(baseContext);
        if (userInput != null) {
            merged.putAll(userInput);
        }
        return asyncTaskManager.submitRecoverable(taskId, recoverableType(def.key()), def.key(), result.getTitle(),
                workflowPayload(merged, fromIndex, userInput == null ? Map.of() : userInput), null);
    }

    private Map<String, Object> workflowPayload(Map<String, Object> context,
                                                int fromIndex,
                                                Map<String, Object> userInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowContext", context == null ? Map.of() : context);
        payload.put("fromIndex", fromIndex);
        payload.put("userInput", userInput == null ? Map.of() : userInput);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Object executePersisted(AsyncTaskExecutionContext context, WorkflowDefinition definition) {
        Map<String, Object> payload = context.arguments();
        Map<String, Object> workflowContext = payload.get("workflowContext") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : new LinkedHashMap<>();
        int fromIndex = payload.get("fromIndex") instanceof Number number ? number.intValue() : 0;
        Map<String, Object> userInput = payload.get("userInput") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        if (workflowStepMapper.findByTaskId(context.taskId()).isEmpty()) {
            createStepRecords(context.taskId(), definition);
        }
        context.stage(fromIndex == 0 ? "正在初始化工作流…" : "正在恢复工作流…");
        return runSteps(context.taskId(), definition, workflowContext, fromIndex, userInput, context::stage);
    }

    private void resetSteps(List<WorkflowStepRecord> stepRecords, int fromIndex) {
        for (int i = fromIndex; i < stepRecords.size(); i++) {
            WorkflowStepRecord r = stepRecords.get(i);
            r.setStatus(AsyncTaskStatus.PENDING.name());
            r.setInputJson(null);
            r.setOutputJson(null);
            r.setError(null);
            r.setStartedAt(null);
            r.setCompletedAt(null);
            r.setUpdatedAt(LocalDateTime.now());
            workflowStepMapper.updateById(r);
        }
    }

    private int firstNonCompletedIndex(List<WorkflowStepRecord> stepRecords) {
        for (int i = 0; i < stepRecords.size(); i++) {
            if (!AsyncTaskStatus.COMPLETED.name().equals(stepRecords.get(i).getStatus())) {
                return i;
            }
        }
        return stepRecords.size();
    }

    private Object runSteps(String taskId, WorkflowDefinition def, Map<String, Object> context,
                            int fromIndex, Map<String, Object> userInput, Consumer<String> setStage) {
        List<WorkflowStepRecord> stepRecords = workflowStepMapper.findByTaskId(taskId);
        List<Object> results = new ArrayList<>();

        // 已经完成的步骤直接复用输出
        for (int idx = 0; idx < fromIndex; idx++) {
            results.add(fromJson(stepRecords.get(idx).getOutputJson()));
        }

        if (fromIndex > 0) {
            setStage.accept("正在从步骤 " + (fromIndex + 1) + " 继续…");
        }

        for (int idx = fromIndex; idx < def.steps().size(); idx++) {
            Object output = executeStep(taskId, def, idx, context, userInput, results, setStage);
            results.add(output);

            WorkflowStepDefinition stepDef = def.steps().get(idx);
            if (stepDef.awaitUserInput()) {
                setStage.accept("等待用户确认…");
                Object partial = buildResult(def, results);
                asyncTaskManager.setPendingUser(taskId, partial);
                return partial;
            }
        }

        return buildResult(def, results);
    }

    private void createStepRecords(String taskId, WorkflowDefinition def) {
        for (int i = 0; i < def.steps().size(); i++) {
            WorkflowStepRecord record = new WorkflowStepRecord();
            record.setTaskId(taskId);
            record.setStepIndex(i);
            record.setStepName(def.steps().get(i).name());
            record.setSkillName(def.steps().get(i).skill());
            record.setStatus(AsyncTaskStatus.PENDING.name());
            workflowStepMapper.insert(record);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object executeStep(String taskId, WorkflowDefinition def, int index,
                               Map<String, Object> context, Map<String, Object> userInput,
                               List<Object> results, Consumer<String> setStage) {
        WorkflowStepDefinition stepDef = def.steps().get(index);
        WorkflowStepRecord record = workflowStepMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WorkflowStepRecord>()
                        .eq("task_id", taskId)
                        .eq("step_index", index));
        if (record == null) {
            throw new WorkflowException("工作流步骤记录不存在: taskId=" + taskId + ", index=" + index);
        }

        record.setStatus(AsyncTaskStatus.PROCESSING.name());
        record.setStartedAt(LocalDateTime.now());
        workflowStepMapper.updateById(record);

        setStage.accept("步骤 " + (index + 1) + "/" + def.steps().size() + "：" + stepDef.name());

        try {
            Skill skill = skillRegistry.get(stepDef.skill());
            if (skill == null) {
                throw new WorkflowException("未知 Skill: " + stepDef.skill());
            }

            Map<String, Object> resolved = argumentResolver.resolve(stepDef.arguments(), context, userInput, results);
            record.setInputJson(toJson(resolved));
            Object input = objectMapper.convertValue(resolved, skill.inputType());
            Object output = skill.execute(new SkillContext(taskId, setStage), input);

            record.setStatus(AsyncTaskStatus.COMPLETED.name());
            record.setOutputJson(toJson(output));
            record.setCompletedAt(LocalDateTime.now());
            workflowStepMapper.updateById(record);
            return output;
        } catch (Exception e) {
            String message = e instanceof WorkflowException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            record.setStatus(AsyncTaskStatus.FAILED.name());
            record.setError(message);
            record.setCompletedAt(LocalDateTime.now());
            workflowStepMapper.updateById(record);
            throw new WorkflowException("步骤 [" + stepDef.name() + "] 执行失败: " + message, e);
        }
    }

    private Object buildResult(WorkflowDefinition def, List<Object> results) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < def.steps().size(); i++) {
            String key = def.steps().get(i).outputKey();
            if (key != null && !key.isBlank()) {
                result.put(key, results.get(i));
            }
        }
        return result.isEmpty() ? results.get(results.size() - 1) : result;
    }

    private Map<String, Object> loadContext(String taskId) {
        com.research.assistant.entity.AsyncTaskRecord record = asyncTaskRecordMapper.selectByTaskId(taskId);
        if (record == null || record.getContextJson() == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> context = objectMapper.readValue(record.getContextJson(), Map.class);
            Object persistedContext = context.get("workflowContext");
            if (persistedContext instanceof Map<?, ?> map) {
                return objectMapper.convertValue(map, Map.class);
            }
            return context;
        } catch (JsonProcessingException e) {
            log.warn("反序列化工作流上下文失败 taskId={}: {}", taskId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String contextJson(String taskId) {
        com.research.assistant.entity.AsyncTaskRecord record = asyncTaskRecordMapper.selectByTaskId(taskId);
        return record != null ? record.getContextJson() : null;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("序列化工作流数据失败: {}", e.getMessage());
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
            log.warn("反序列化工作流数据失败: {}", e.getMessage());
            return null;
        }
    }
}
