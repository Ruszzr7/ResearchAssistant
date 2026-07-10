package com.research.assistant.service.ai.plan;

import java.util.List;

/**
 * LLM 生成的执行计划。
 */
public record Plan(List<PlanStep> steps) {
}
