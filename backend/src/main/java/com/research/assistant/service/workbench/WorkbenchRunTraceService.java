package com.research.assistant.service.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import com.research.assistant.entity.PaperWorkbenchStepRecord;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.PaperWorkbenchStepMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists the bounded workbench plan and its execution trace independently of model availability. */
@Service
public class WorkbenchRunTraceService {

    private static final TypeReference<List<WorkbenchPlan.ArtifactVersion>> ARTIFACT_LIST = new TypeReference<>() { };

    private final PaperWorkbenchRunMapper runMapper;
    private final PaperWorkbenchStepMapper stepMapper;
    private final WorkbenchRuleRouter router;
    private final PaperLayoutArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public WorkbenchRunTraceService(PaperWorkbenchRunMapper runMapper,
                                    PaperWorkbenchStepMapper stepMapper,
                                    WorkbenchRuleRouter router,
                                    PaperLayoutArtifactService artifactService,
                                    ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.router = router;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkbenchRunTrace plan(WorkbenchInvocation invocation) {
        WorkbenchPlan plan = router.route(invocation);
        List<WorkbenchPlan.ArtifactVersion> artifactVersions = ensureArtifacts(invocation);
        validateSelectionVersion(invocation.selectionAnchor(), artifactVersions);

        String runId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        PaperWorkbenchRunRecord run = new PaperWorkbenchRunRecord();
        run.setRunId(runId);
        run.setWorkflow(plan.workflow().name());
        run.setScope(plan.scope().name());
        run.setStatus(WorkbenchRunStatus.PLANNED.name());
        run.setPaperIdsJson(write(invocation.paperIds()));
        run.setRequestJson(write(invocation));
        run.setPlanJson(write(plan));
        run.setArtifactVersionsJson(write(artifactVersions));
        run.setEvidenceRequired(plan.evidenceRequired());
        run.setMaxSteps(plan.maxSteps());
        run.setTokenBudget(plan.tokenBudget());
        run.setRepairCount(0);
        run.setEvidenceCount(0);
        run.setPromptTokens(0);
        run.setCompletionTokens(0);
        run.setTotalTokens(0);
        run.setLatencyMs(0L);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        for (WorkbenchPlan.Step planStep : plan.steps()) {
            PaperWorkbenchStepRecord step = new PaperWorkbenchStepRecord();
            step.setRunId(runId);
            step.setStepIndex(planStep.index());
            step.setStepName(planStep.name());
            step.setSkillName(planStep.skill().name());
            step.setStepKind(planStep.kind().name());
            step.setStatus(WorkbenchStepStatus.PENDING.name());
            step.setEvidenceCount(0);
            step.setRetryCount(0);
            step.setPromptTokens(0);
            step.setCompletionTokens(0);
            step.setTotalTokens(0);
            step.setLatencyMs(0L);
            step.setCreatedAt(now);
            step.setUpdatedAt(now);
            stepMapper.insert(step);
        }
        return requireTrace(runId);
    }

    public WorkbenchRunTrace findTrace(String runId) {
        if (runId == null || runId.isBlank()) return null;
        PaperWorkbenchRunRecord run = runMapper.selectByRunId(runId);
        return run == null ? null : toTrace(run, stepMapper.findByRunId(runId));
    }

    public WorkbenchRunTrace requireTrace(String runId) {
        WorkbenchRunTrace trace = findTrace(runId);
        if (trace == null) throw new IllegalArgumentException("workbench run does not exist");
        return trace;
    }

    @Transactional
    public void markQueued(String runId, String taskId) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        requireRunStatus(run, WorkbenchRunStatus.PLANNED);
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        run.setTaskId(taskId);
        run.setStatus(WorkbenchRunStatus.QUEUED.name());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    @Transactional
    public void startRun(String runId, String taskId) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        WorkbenchRunStatus current = WorkbenchRunStatus.valueOf(run.getStatus());
        if (current != WorkbenchRunStatus.PLANNED && current != WorkbenchRunStatus.QUEUED) {
            throw new IllegalStateException("run cannot start from " + current);
        }
        LocalDateTime now = LocalDateTime.now();
        run.setTaskId(taskId == null ? run.getTaskId() : taskId);
        run.setStatus(WorkbenchRunStatus.RUNNING.name());
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
    }

    /** Starts a queued run or repairs an interrupted task attempt without replaying completed deterministic steps. */
    @Transactional
    public WorkbenchRunTrace prepareExecutionAttempt(String runId, String taskId) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        WorkbenchRunStatus status = WorkbenchRunStatus.valueOf(run.getStatus());
        if (status == WorkbenchRunStatus.COMPLETED) return toTrace(run, stepMapper.findByRunId(runId));
        if (status == WorkbenchRunStatus.FAILED || status == WorkbenchRunStatus.CANCELLED) {
            throw new IllegalStateException("terminal run cannot resume");
        }
        if (run.getTaskId() != null && taskId != null && !run.getTaskId().equals(taskId)) {
            throw new IllegalStateException("run is already bound to another task");
        }
        LocalDateTime now = LocalDateTime.now();
        if (status == WorkbenchRunStatus.PLANNED || status == WorkbenchRunStatus.QUEUED) {
            run.setStatus(WorkbenchRunStatus.RUNNING.name());
            run.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        }
        if (taskId != null) run.setTaskId(taskId);
        run.setUpdatedAt(now);
        runMapper.updateById(run);

        List<PaperWorkbenchStepRecord> steps = stepMapper.findByRunId(runId);
        for (PaperWorkbenchStepRecord step : steps) {
            WorkbenchStepStatus stepStatus = WorkbenchStepStatus.valueOf(step.getStatus());
            if (stepStatus == WorkbenchStepStatus.RUNNING || stepStatus == WorkbenchStepStatus.FAILED) {
                resetStepForTaskRetry(step);
            }
        }
        PaperWorkbenchStepRecord gate = steps.stream()
                .filter(step -> WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER.name().equals(step.getSkillName()))
                .findFirst().orElse(null);
        if (gate != null) {
            WorkbenchStepStatus gateStatus = WorkbenchStepStatus.valueOf(gate.getStatus());
            boolean hasApprovedCheckpoint = run.getResultJson() != null && !run.getResultJson().isBlank();
            if (gateStatus != WorkbenchStepStatus.COMPLETED
                    || gateStatus == WorkbenchStepStatus.COMPLETED && !hasApprovedCheckpoint) {
                steps.stream()
                        .filter(step -> WorkbenchPlan.StepKind.LLM.name().equals(step.getStepKind()))
                        .filter(step -> WorkbenchStepStatus.valueOf(step.getStatus()) == WorkbenchStepStatus.COMPLETED)
                        .findFirst()
                        .ifPresent(this::resetStepForTaskRetry);
            }
            // A process can stop after the gate row commits but before the approved result checkpoint commits.
            // Raw model output is intentionally not persisted, so that narrow crash window must replay model + gate.
            if (gateStatus == WorkbenchStepStatus.COMPLETED && !hasApprovedCheckpoint) {
                resetStepForTaskRetry(gate);
            }
        }
        return requireTrace(runId);
    }

    @Transactional
    public void startStep(String runId, int stepIndex, Object inputSummary) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        requireRunStatus(run, WorkbenchRunStatus.RUNNING);
        PaperWorkbenchStepRecord step = requireStep(runId, stepIndex);
        WorkbenchStepStatus status = WorkbenchStepStatus.valueOf(step.getStatus());
        if (status != WorkbenchStepStatus.PENDING) {
            throw new IllegalStateException("step cannot start from " + status);
        }
        LocalDateTime now = LocalDateTime.now();
        step.setStatus(WorkbenchStepStatus.RUNNING.name());
        step.setInputSummaryJson(writeNullable(inputSummary));
        step.setStartedAt(now);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
    }

    @Transactional
    public void completeStep(String runId, int stepIndex, Object outputSummary,
                             int evidenceCount, int promptTokens, int completionTokens, long latencyMs) {
        requireRunStatus(requireRun(runId), WorkbenchRunStatus.RUNNING);
        PaperWorkbenchStepRecord step = requireStep(runId, stepIndex);
        requireStepStatus(step, WorkbenchStepStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        step.setStatus(WorkbenchStepStatus.COMPLETED.name());
        step.setOutputSummaryJson(writeNullable(outputSummary));
        step.setEvidenceCount(Math.max(value(step.getEvidenceCount()), nonNegative(evidenceCount)));
        step.setPromptTokens(value(step.getPromptTokens()) + nonNegative(promptTokens));
        step.setCompletionTokens(value(step.getCompletionTokens()) + nonNegative(completionTokens));
        step.setTotalTokens(value(step.getTotalTokens()) + nonNegative(promptTokens) + nonNegative(completionTokens));
        step.setLatencyMs(longValue(step.getLatencyMs()) + Math.max(0, latencyMs));
        step.setCompletedAt(now);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
    }

    @Transactional
    public void failStep(String runId, int stepIndex, String errorCode, String safeErrorMessage, long latencyMs) {
        requireRunStatus(requireRun(runId), WorkbenchRunStatus.RUNNING);
        PaperWorkbenchStepRecord step = requireStep(runId, stepIndex);
        requireStepStatus(step, WorkbenchStepStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        step.setStatus(WorkbenchStepStatus.FAILED.name());
        step.setErrorCode(normalizeCode(errorCode));
        step.setErrorMessage(truncate(safeErrorMessage, 1_000));
        step.setLatencyMs(longValue(step.getLatencyMs()) + Math.max(0, latencyMs));
        step.setCompletedAt(now);
        step.setUpdatedAt(now);
        stepMapper.updateById(step);
    }

    /** Reopens the generation and failed gate steps; a second repair is forbidden. */
    @Transactional
    public void prepareSingleRepair(String runId, int generationStepIndex, int gateStepIndex) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        requireRunStatus(run, WorkbenchRunStatus.RUNNING);
        if (value(run.getRepairCount()) >= 1) {
            throw new IllegalStateException("workbench evidence repair limit reached");
        }
        PaperWorkbenchStepRecord generationStep = requireStep(runId, generationStepIndex);
        PaperWorkbenchStepRecord gateStep = requireStep(runId, gateStepIndex);
        requireStepStatus(generationStep, WorkbenchStepStatus.COMPLETED);
        requireStepStatus(gateStep, WorkbenchStepStatus.FAILED);
        run.setRepairCount(1);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);

        resetStepForRepair(generationStep);
        resetStepForRepair(gateStep);
    }

    @Transactional
    public void completeRun(String runId, Object result, int evidenceCount) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        requireRunStatus(run, WorkbenchRunStatus.RUNNING);
        List<PaperWorkbenchStepRecord> steps = stepMapper.findByRunId(runId);
        if (steps.stream().anyMatch(step -> WorkbenchStepStatus.valueOf(step.getStatus()) != WorkbenchStepStatus.COMPLETED
                && WorkbenchStepStatus.valueOf(step.getStatus()) != WorkbenchStepStatus.SKIPPED)) {
            throw new IllegalStateException("run cannot complete while steps are unfinished");
        }
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(WorkbenchRunStatus.COMPLETED.name());
        if (result != null) run.setResultJson(writeNullable(result));
        run.setEvidenceCount(nonNegative(evidenceCount));
        run.setPromptTokens(steps.stream().mapToInt(step -> value(step.getPromptTokens())).sum());
        run.setCompletionTokens(steps.stream().mapToInt(step -> value(step.getCompletionTokens())).sum());
        run.setTotalTokens(steps.stream().mapToInt(step -> value(step.getTotalTokens())).sum());
        run.setLatencyMs(elapsed(run.getStartedAt(), now));
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
    }

    /** Stores only a normalized, gate-approved product result for crash recovery. */
    @Transactional
    public void checkpointResult(String runId, Object result) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        requireRunStatus(run, WorkbenchRunStatus.RUNNING);
        run.setResultJson(writeNullable(result));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    @Transactional
    public void failRun(String runId, String errorCode, String safeErrorMessage) {
        PaperWorkbenchRunRecord run = requireRun(runId);
        WorkbenchRunStatus status = WorkbenchRunStatus.valueOf(run.getStatus());
        if (status == WorkbenchRunStatus.COMPLETED || status == WorkbenchRunStatus.CANCELLED
                || status == WorkbenchRunStatus.FAILED) {
            throw new IllegalStateException("terminal run cannot fail");
        }
        List<PaperWorkbenchStepRecord> steps = stepMapper.findByRunId(runId);
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(WorkbenchRunStatus.FAILED.name());
        run.setErrorCode(normalizeCode(errorCode));
        run.setErrorMessage(truncate(safeErrorMessage, 1_000));
        run.setEvidenceCount(steps.stream().mapToInt(step -> value(step.getEvidenceCount())).max().orElse(0));
        run.setPromptTokens(steps.stream().mapToInt(step -> value(step.getPromptTokens())).sum());
        run.setCompletionTokens(steps.stream().mapToInt(step -> value(step.getCompletionTokens())).sum());
        run.setTotalTokens(steps.stream().mapToInt(step -> value(step.getTotalTokens())).sum());
        run.setLatencyMs(elapsed(run.getStartedAt(), now));
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
    }

    private List<WorkbenchPlan.ArtifactVersion> ensureArtifacts(WorkbenchInvocation invocation) {
        List<WorkbenchPlan.ArtifactVersion> versions = new ArrayList<>();
        for (Long paperId : invocation.paperIds()) {
            PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
            versions.add(new WorkbenchPlan.ArtifactVersion(
                    paperId, artifact.documentHash(), artifact.parserVersion(), artifact.layoutConfidence()));
        }
        return List.copyOf(versions);
    }

    private void validateSelectionVersion(SelectionAnchor anchor,
                                          List<WorkbenchPlan.ArtifactVersion> artifactVersions) {
        if (anchor == null) return;
        WorkbenchPlan.ArtifactVersion version = artifactVersions.stream()
                .filter(item -> item.paperId().equals(anchor.paperId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selection anchor paper is outside the run"));
        if (!version.documentHash().equals(anchor.documentHash())
                || !version.parserVersion().equals(anchor.parserVersion())) {
            throw new StaleLayoutArtifactException();
        }
    }

    private WorkbenchRunTrace toTrace(PaperWorkbenchRunRecord run, List<PaperWorkbenchStepRecord> steps) {
        WorkbenchInvocation invocation = read(run.getRequestJson(), WorkbenchInvocation.class);
        WorkbenchPlan plan = read(run.getPlanJson(), WorkbenchPlan.class);
        List<WorkbenchPlan.ArtifactVersion> versions = read(run.getArtifactVersionsJson(), ARTIFACT_LIST);
        Object result = readNullable(run.getResultJson(), Object.class);
        List<WorkbenchRunTrace.StepTrace> stepTraces = steps.stream().map(step -> new WorkbenchRunTrace.StepTrace(
                step.getStepIndex(), step.getStepName(), WorkbenchPlan.Skill.valueOf(step.getSkillName()),
                WorkbenchPlan.StepKind.valueOf(step.getStepKind()), WorkbenchStepStatus.valueOf(step.getStatus()),
                value(step.getEvidenceCount()), value(step.getRetryCount()), value(step.getPromptTokens()),
                value(step.getCompletionTokens()), value(step.getTotalTokens()), longValue(step.getLatencyMs()),
                readNullable(step.getInputSummaryJson(), Object.class),
                readNullable(step.getOutputSummaryJson(), Object.class), step.getErrorCode(), step.getErrorMessage(),
                step.getStartedAt(), step.getCompletedAt())).toList();
        return new WorkbenchRunTrace(
                run.getRunId(), run.getTaskId(), WorkbenchRunStatus.valueOf(run.getStatus()), invocation, plan, versions,
                new WorkbenchRunTrace.Metrics(value(run.getEvidenceCount()), value(run.getRepairCount()),
                        value(run.getPromptTokens()), value(run.getCompletionTokens()), value(run.getTotalTokens()),
                        longValue(run.getLatencyMs())),
                result, run.getErrorCode(), run.getErrorMessage(), run.getStartedAt(), run.getCompletedAt(),
                run.getCreatedAt(), run.getUpdatedAt(), stepTraces);
    }

    private PaperWorkbenchRunRecord requireRun(String runId) {
        PaperWorkbenchRunRecord run = runMapper.selectByRunId(runId);
        if (run == null) throw new IllegalArgumentException("workbench run does not exist");
        return run;
    }

    private PaperWorkbenchStepRecord requireStep(String runId, int stepIndex) {
        PaperWorkbenchStepRecord step = stepMapper.selectByRunAndIndex(runId, stepIndex);
        if (step == null) throw new IllegalArgumentException("workbench step does not exist");
        return step;
    }

    private void requireRunStatus(PaperWorkbenchRunRecord run, WorkbenchRunStatus expected) {
        WorkbenchRunStatus actual = WorkbenchRunStatus.valueOf(run.getStatus());
        if (actual != expected) throw new IllegalStateException("run state is " + actual + ", expected " + expected);
    }

    private void requireStepStatus(PaperWorkbenchStepRecord step, WorkbenchStepStatus expected) {
        WorkbenchStepStatus actual = WorkbenchStepStatus.valueOf(step.getStatus());
        if (actual != expected) throw new IllegalStateException("step state is " + actual + ", expected " + expected);
    }

    private void resetStepForRepair(PaperWorkbenchStepRecord step) {
        step.setStatus(WorkbenchStepStatus.PENDING.name());
        step.setRetryCount(value(step.getRetryCount()) + 1);
        step.setOutputSummaryJson(null);
        step.setErrorCode(null);
        step.setErrorMessage(null);
        step.setStartedAt(null);
        step.setCompletedAt(null);
        step.setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(step);
    }

    private void resetStepForTaskRetry(PaperWorkbenchStepRecord step) {
        step.setStatus(WorkbenchStepStatus.PENDING.name());
        step.setRetryCount(value(step.getRetryCount()) + 1);
        step.setInputSummaryJson(null);
        step.setOutputSummaryJson(null);
        step.setErrorCode(null);
        step.setErrorMessage(null);
        step.setStartedAt(null);
        step.setCompletedAt(null);
        step.setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(step);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workbench trace cannot be serialized", e);
        }
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workbench trace cannot be read", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workbench trace cannot be read", e);
        }
    }

    private <T> T readNullable(String json, Class<T> type) {
        return json == null || json.isBlank() ? null : read(json, type);
    }

    private int nonNegative(int value) { return Math.max(0, value); }
    private int value(Integer value) { return value == null ? 0 : value; }
    private long longValue(Long value) { return value == null ? 0 : value; }
    private long elapsed(LocalDateTime start, LocalDateTime end) {
        return start == null ? 0 : Math.max(0, Duration.between(start, end).toMillis());
    }
    private String normalizeCode(String value) {
        String normalized = value == null ? "WORKBENCH_FAILED" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : "WORKBENCH_FAILED";
    }
    private String truncate(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
