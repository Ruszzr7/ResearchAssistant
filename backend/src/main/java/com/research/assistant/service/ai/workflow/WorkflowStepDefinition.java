package com.research.assistant.service.ai.workflow;

import java.util.Map;

/**
 * 工作流中的单一步骤定义。
 */
public record WorkflowStepDefinition(
        String name,
        String skill,
        Map<String, Object> arguments,
        String outputKey,
        boolean awaitUserInput
) {
    public WorkflowStepDefinition(String name, String skill, Map<String, Object> arguments) {
        this(name, skill, arguments, null, false);
    }

    public WorkflowStepDefinition(String name, String skill, Map<String, Object> arguments, String outputKey) {
        this(name, skill, arguments, outputKey, false);
    }

    public WorkflowStepDefinition(String name, String skill, Map<String, Object> arguments,
                                  String outputKey, boolean awaitUserInput) {
        this.name = name;
        this.skill = skill;
        this.arguments = arguments;
        this.outputKey = outputKey;
        this.awaitUserInput = awaitUserInput;
    }
}
