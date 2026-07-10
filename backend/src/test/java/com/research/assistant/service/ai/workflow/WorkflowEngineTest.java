package com.research.assistant.service.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.entity.WorkflowStepRecord;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.WorkflowStepMapper;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.ai.skill.SkillRegistry;
import com.research.assistant.service.async.AsyncTaskManager;
import com.research.assistant.service.async.AsyncTaskResult;
import com.research.assistant.service.async.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * {@link WorkflowEngine} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock
    private AsyncTaskManager asyncTaskManager;

    @Mock
    private WorkflowStepMapper workflowStepMapper;

    @Mock
    private AsyncTaskRecordMapper asyncTaskRecordMapper;

    @Mock
    private SkillRegistry skillRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowEngine engine() {
        WorkflowRegistry registry = new WorkflowRegistry();
        WorkflowArgumentResolver resolver = new WorkflowArgumentResolver(objectMapper);
        return new WorkflowEngine(asyncTaskManager, registry, workflowStepMapper,
                asyncTaskRecordMapper, resolver, skillRegistry, objectMapper);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldExecuteAllStepsAndBuildResult() throws Exception {
        // 注册 mock Skill
        Skill echoSkill = new EchoSkill();
        Skill wrapSkill = new WrapSkill();
        doReturn(echoSkill).when(skillRegistry).get("echo");
        doReturn(wrapSkill).when(skillRegistry).get("wrap");

        // 用自定义 workflow 覆盖默认 registry？不，这里直接测试默认 gap-research 太复杂。
        // 改为构造一个测试用的 WorkflowDefinition，通过反射替换 registry 中的定义。
        WorkflowEngine engine = engine();
        replaceRegistryWith(engine, new WorkflowDefinition(
                "gap-research", "test", "test",
                List.of(
                        new WorkflowStepDefinition("echo", "echo", Map.of("value", "{{context.value}}"), "out1"),
                        new WorkflowStepDefinition("wrap", "wrap", Map.of("text", "{{prev}}"), "out2")
                )
        ));

        // 捕获提交的工作流任务函数并同步执行
        AtomicReference<BiFunction<String, Consumer<String>, Object>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            return "task-1";
        }).when(asyncTaskManager).submit(anyString(), anyString(), anyString(), any(BiFunction.class));

        lenient().doAnswer(invocation -> {
            WorkflowStepRecord r = new WorkflowStepRecord();
            r.setTaskId("task-1");
            return r;
        }).when(workflowStepMapper).selectOne(any());

        String taskId = engine.submit("gap-research", Map.of("value", "hello"));
        assertThat(taskId).isEqualTo("task-1");

        Object result = captured.get().apply("task-1", s -> {});

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("out1", "hello");
        assertThat(map).containsEntry("out2", "[hello]");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldFailWorkflowWhenStepFails() throws Exception {
        Skill echoSkill = new EchoSkill();
        Skill failingSkill = new FailingSkill();
        doReturn(echoSkill).when(skillRegistry).get("echo");
        doReturn(failingSkill).when(skillRegistry).get("failing");

        WorkflowEngine engine = engine();
        replaceRegistryWith(engine, new WorkflowDefinition(
                "gap-research", "test", "test",
                List.of(
                        new WorkflowStepDefinition("echo", "echo", Map.of("value", "x"), null),
                        new WorkflowStepDefinition("failing", "failing", Map.of(), null)
                )
        ));

        AtomicReference<BiFunction<String, Consumer<String>, Object>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            return "task-2";
        }).when(asyncTaskManager).submit(anyString(), anyString(), anyString(), any(BiFunction.class));

        lenient().doAnswer(invocation -> new WorkflowStepRecord()).when(workflowStepMapper).selectOne(any());

        engine.submit("gap-research", Map.of());

        boolean thrown = false;
        try {
            captured.get().apply("task-2", s -> {});
        } catch (WorkflowException e) {
            thrown = true;
            assertThat(e.getMessage()).contains("failing");
        }
        assertThat(thrown).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldRetryFromFailedStep() throws Exception {
        Skill echoSkill = new EchoSkill();
        doReturn(echoSkill).when(skillRegistry).get("echo");

        WorkflowEngine engine = engine();
        replaceRegistryWith(engine, new WorkflowDefinition(
                "gap-research", "test", "test",
                List.of(
                        new WorkflowStepDefinition("echo", "echo", Map.of("value", "{{context.value}}"), "out1"),
                        new WorkflowStepDefinition("echo", "echo", Map.of("value", "retry"), "out2")
                )
        ));

        AsyncTaskResult<?> failedResult = AsyncTaskResult.pending("task-3", "排队中…", "gap-research", null, "test")
                .failed("boom");
        doReturn(failedResult).when(asyncTaskManager).get("task-3");

        AsyncTaskRecord record = new AsyncTaskRecord();
        record.setTaskId("task-3");
        record.setContextJson(objectMapper.writeValueAsString(Map.of("value", "hello")));
        doReturn(record).when(asyncTaskRecordMapper).selectByTaskId("task-3");

        WorkflowStepRecord step0 = new WorkflowStepRecord();
        step0.setStepIndex(0);
        step0.setStatus(AsyncTaskStatus.COMPLETED.name());
        step0.setOutputJson("\"hello\"");
        WorkflowStepRecord step1 = new WorkflowStepRecord();
        step1.setStepIndex(1);
        step1.setStatus(AsyncTaskStatus.FAILED.name());
        doReturn(List.of(step0, step1)).when(workflowStepMapper).findByTaskId("task-3");

        AtomicReference<BiFunction<String, Consumer<String>, Object>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(4));
            return "task-3";
        }).when(asyncTaskManager).submit(anyString(), anyString(), anyString(), anyString(), any(BiFunction.class));

        lenient().doAnswer(invocation -> new WorkflowStepRecord()).when(workflowStepMapper).selectOne(any());

        engine.retry("task-3");

        Object result = captured.get().apply("task-3", s -> {});
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("out1", "hello");
        assertThat(map).containsEntry("out2", "retry");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldPauseWorkflowForUserInput() throws Exception {
        Skill echoSkill = new EchoSkill();
        doReturn(echoSkill).when(skillRegistry).get("echo");

        WorkflowEngine engine = engine();
        replaceRegistryWith(engine, new WorkflowDefinition(
                "gap-research", "test", "test",
                List.of(
                        new WorkflowStepDefinition("echo", "echo", Map.of("value", "x"), "out1"),
                        new WorkflowStepDefinition("pause", "echo", Map.of("value", "pause"), "out2", true)
                )
        ));

        AtomicReference<BiFunction<String, Consumer<String>, Object>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            return "task-pause";
        }).when(asyncTaskManager).submit(anyString(), anyString(), anyString(), any(BiFunction.class));

        lenient().doAnswer(invocation -> new WorkflowStepRecord()).when(workflowStepMapper).selectOne(any());
        doNothing().when(asyncTaskManager).setPendingUser(anyString(), any());

        engine.submit("gap-research", Map.of());

        Object result = captured.get().apply("task-pause", s -> {});
        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("out1", "x");
        assertThat(map).containsEntry("out2", "pause");
        verify(asyncTaskManager).setPendingUser(eq("task-pause"), any());
    }

    private void replaceRegistryWith(WorkflowEngine engine, WorkflowDefinition def) throws Exception {
        java.lang.reflect.Field field = WorkflowEngine.class.getDeclaredField("workflowRegistry");
        field.setAccessible(true);
        WorkflowRegistry registry = new WorkflowRegistry();
        java.lang.reflect.Field mapField = WorkflowRegistry.class.getDeclaredField("definitions");
        mapField.setAccessible(true);
        mapField.set(registry, Map.of(def.key(), def));
        field.set(engine, registry);
    }

    private record EchoInput(String value) {
    }

    private static class EchoSkill implements Skill<EchoInput, String> {
        @Override
        public String name() { return "echo"; }
        @Override
        public String description() { return "echo"; }
        @Override
        public Class<EchoInput> inputType() { return EchoInput.class; }
        @Override
        public String execute(SkillContext ctx, EchoInput input) { return input.value(); }
    }

    private record WrapInput(String text) {
    }

    private static class WrapSkill implements Skill<WrapInput, String> {
        @Override
        public String name() { return "wrap"; }
        @Override
        public String description() { return "wrap"; }
        @Override
        public Class<WrapInput> inputType() { return WrapInput.class; }
        @Override
        public String execute(SkillContext ctx, WrapInput input) { return "[" + input.text() + "]"; }
    }

    private static class FailingSkill implements Skill<Object, Object> {
        @Override
        public String name() { return "failing"; }
        @Override
        public String description() { return "failing"; }
        @Override
        public Class<Object> inputType() { return Object.class; }
        @Override
        public Object execute(SkillContext ctx, Object input) { throw new RuntimeException("always fails"); }
    }
}
