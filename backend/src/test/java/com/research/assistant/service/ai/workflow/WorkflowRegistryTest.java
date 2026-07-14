package com.research.assistant.service.ai.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkflowRegistryTest {

    @Test
    void paperImportOnlyProducesImportRecommendations() {
        WorkflowDefinition definition = new WorkflowRegistry().get("paper-import");

        assertEquals(3, definition.steps().size());
        assertEquals("文件夹推荐", definition.steps().get(2).name());
        assertFalse(definition.steps().stream().anyMatch(step -> "深度分析".equals(step.name())));
    }
}
