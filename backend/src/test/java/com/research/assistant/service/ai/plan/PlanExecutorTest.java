package com.research.assistant.service.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.ai.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * {@link PlanExecutor} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlanExecutorTest {

    @Mock
    private SkillRegistry skillRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PlanExecutor executor() {
        return new PlanExecutor(skillRegistry, objectMapper);
    }

    @Test
    void shouldExecuteSingleStep() {
        doReturn(new EchoSkill()).when(skillRegistry).get("echo");

        Plan plan = new Plan(List.of(new PlanStep("echo", Map.of("value", "hello"))));
        Object result = executor().execute(plan, new SkillContext(null));

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void shouldInjectPreviousResultWithPrevPlaceholder() {
        doReturn(new EchoSkill()).when(skillRegistry).get("echo");
        doReturn(new WrapSkill()).when(skillRegistry).get("wrap");

        Plan plan = new Plan(List.of(
                new PlanStep("echo", Map.of("value", "world")),
                new PlanStep("wrap", Map.of("text", "{{prev}}"))
        ));
        Object result = executor().execute(plan, new SkillContext(null));

        assertThat(result).isEqualTo("[world]");
    }

    @Test
    void shouldInjectStepResultByIndex() {
        doReturn(new EchoSkill()).when(skillRegistry).get("echo");
        doReturn(new WrapSkill()).when(skillRegistry).get("wrap");

        Plan plan = new Plan(List.of(
                new PlanStep("echo", Map.of("value", "index")),
                new PlanStep("wrap", Map.of("text", "{{step0}}"))
        ));
        Object result = executor().execute(plan, new SkillContext(null));

        assertThat(result).isEqualTo("[index]");
    }

    @Test
    void shouldEmbedPlaceholderInString() {
        doReturn(new EchoSkill()).when(skillRegistry).get("echo");
        doReturn(new WrapSkill()).when(skillRegistry).get("wrap");

        Plan plan = new Plan(List.of(
                new PlanStep("echo", Map.of("value", "X")),
                new PlanStep("wrap", Map.of("text", "pre-{{prev}}-post"))
        ));
        Object result = executor().execute(plan, new SkillContext(null));

        assertThat(result).isEqualTo("[pre-X-post]");
    }

    private record EchoInput(String value) {
    }

    private static class EchoSkill implements Skill<EchoInput, String> {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显输入";
        }

        @Override
        public Class<EchoInput> inputType() {
            return EchoInput.class;
        }

        @Override
        public String execute(SkillContext ctx, EchoInput input) {
            return input.value();
        }
    }

    private record WrapInput(String text) {
    }

    private static class WrapSkill implements Skill<WrapInput, String> {
        @Override
        public String name() {
            return "wrap";
        }

        @Override
        public String description() {
            return "用方括号包裹文本";
        }

        @Override
        public Class<WrapInput> inputType() {
            return WrapInput.class;
        }

        @Override
        public String execute(SkillContext ctx, WrapInput input) {
            return "[" + input.text() + "]";
        }
    }
}
