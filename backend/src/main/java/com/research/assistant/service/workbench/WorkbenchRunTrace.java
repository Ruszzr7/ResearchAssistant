package com.research.assistant.service.workbench;

import java.time.LocalDateTime;
import java.util.List;

/** API-safe run trace. Step payloads are summaries, never raw prompts or provider responses. */
public record WorkbenchRunTrace(String runId,
                                String taskId,
                                WorkbenchRunStatus status,
                                WorkbenchInvocation invocation,
                                WorkbenchPlan plan,
                                List<WorkbenchPlan.ArtifactVersion> artifactVersions,
                                Metrics metrics,
                                Object result,
                                String errorCode,
                                String errorMessage,
                                LocalDateTime startedAt,
                                LocalDateTime completedAt,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt,
                                List<StepTrace> steps) {
    public WorkbenchRunTrace {
        artifactVersions = artifactVersions == null ? List.of() : List.copyOf(artifactVersions);
        metrics = metrics == null ? new Metrics(0, 0, 0, 0, 0, 0) : metrics;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Metrics(int evidenceCount,
                          int repairCount,
                          int promptTokens,
                          int completionTokens,
                          int totalTokens,
                          long latencyMs) {
    }

    public record StepTrace(int index,
                            String name,
                            WorkbenchPlan.Skill skill,
                            WorkbenchPlan.StepKind kind,
                            WorkbenchStepStatus status,
                            int evidenceCount,
                            int retryCount,
                            int promptTokens,
                            int completionTokens,
                            int totalTokens,
                            long latencyMs,
                            Object inputSummary,
                            Object outputSummary,
                            String errorCode,
                            String errorMessage,
                            LocalDateTime startedAt,
                            LocalDateTime completedAt) {
    }
}
