package com.research.assistant.service.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowArgumentResolver} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowArgumentResolverTest {

    @Mock
    private ObjectMapper objectMapper;

    private WorkflowArgumentResolver resolver() {
        return new WorkflowArgumentResolver(objectMapper);
    }

    @Test
    void shouldResolveContextPlaceholder() {
        Map<String, Object> args = Map.of("paperIds", "{{context.paperIds}}");
        Map<String, Object> context = Map.of("paperIds", List.of(1L, 2L));

        Map<String, Object> resolved = resolver().resolve(args, context, List.of());

        assertThat(resolved).containsEntry("paperIds", List.of(1L, 2L));
    }

    @Test
    void shouldResolvePrevPlaceholder() {
        Map<String, Object> args = Map.of("gapReport", "{{prev}}");
        List<Object> results = List.of("gaps-report");

        Map<String, Object> resolved = resolver().resolve(args, Map.of(), results);

        assertThat(resolved).containsEntry("gapReport", "gaps-report");
    }

    @Test
    void shouldResolveStepPlaceholder() {
        Map<String, Object> args = Map.of("value", "{{step0}}");
        List<Object> results = List.of("first-output");

        Map<String, Object> resolved = resolver().resolve(args, Map.of(), results);

        assertThat(resolved).containsEntry("value", "first-output");
    }

    @Test
    void shouldResolveInputPlaceholder() {
        Map<String, Object> args = Map.of("selected", "{{input.selected}}", "folderId", "{{input.folderId}}");
        Map<String, Object> userInput = Map.of("selected", List.of("a", "b"), "folderId", 7L);

        Map<String, Object> resolved = resolver().resolve(args, Map.of(), userInput, List.of());

        assertThat(resolved).containsEntry("selected", List.of("a", "b"));
        assertThat(resolved).containsEntry("folderId", 7L);
    }

    @Test
    void shouldEmbedPlaceholderInString() {
        Map<String, Object> args = Map.of("text", "pre-{{prev}}-post");
        List<Object> results = List.of("X");

        Map<String, Object> resolved = resolver().resolve(args, Map.of(), results);

        assertThat(resolved).containsEntry("text", "pre-X-post");
    }
}
