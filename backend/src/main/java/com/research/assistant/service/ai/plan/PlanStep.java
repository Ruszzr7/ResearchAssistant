package com.research.assistant.service.ai.plan;

import java.util.Map;

/**
 * 计划中的单一步骤。
 */
public record PlanStep(String skill, Map<String, Object> arguments) {
}
