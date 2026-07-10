package com.research.assistant.service.ai.workflow;

import java.util.List;
import java.util.Map;

/**
 * 工作流定义 —— 固定模板，由代码注册到 {@link WorkflowRegistry}。
 */
public record WorkflowDefinition(
        String key,
        String name,
        String description,
        List<WorkflowStepDefinition> steps
) {
}
