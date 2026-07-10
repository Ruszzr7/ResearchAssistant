package com.research.assistant.service.ai.workflow;

import java.time.LocalDateTime;

/**
 * 工作流步骤视图 —— 返回给前端展示。
 */
public record WorkflowStepView(
        int stepIndex,
        String stepName,
        String skillName,
        String status,
        String error,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
