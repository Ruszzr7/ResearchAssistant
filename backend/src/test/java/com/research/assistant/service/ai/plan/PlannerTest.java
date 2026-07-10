package com.research.assistant.service.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link Planner} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlannerTest {

    @Mock
    private LLMService llmService;

    @Mock
    private SkillRegistry skillRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Planner planner() {
        return new Planner(llmService, skillRegistry, objectMapper);
    }

    @Test
    void shouldParseValidPlanJson() {
        when(skillRegistry.all()).thenReturn(List.of(
                new FakeSkill("analyze-paper", "分析单篇论文"),
                new FakeSkill("compare-papers", "对比多篇论文")
        ));

        when(llmService.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        ```json
                        {"steps": [{"skill": "compare-papers", "arguments": {"paperIds": [1, 2]}}]}
                        ```
                        """);

        Plan plan = planner().plan("对比论文 1 和 2");

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).skill()).isEqualTo("compare-papers");
        assertThat(plan.steps().get(0).arguments()).containsEntry("paperIds", List.of(1, 2));
    }

    @Test
    void shouldReturnEmptyPlanWhenJsonInvalid() {
        when(skillRegistry.all()).thenReturn(List.of(new FakeSkill("analyze-paper", "分析单篇论文")));
        when(llmService.chat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("这不是 JSON");

        Plan plan = planner().plan("随便说说");

        assertThat(plan.steps()).isEmpty();
    }

    private record FakeSkill(String name, String description) implements Skill<Object, Object> {
        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Class<Object> inputType() {
            return Object.class;
        }

        @Override
        public Object execute(com.research.assistant.service.ai.skill.SkillContext ctx, Object input) {
            return null;
        }
    }
}
