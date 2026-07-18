package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.memory.PaperMemoryObservationService;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Executes exactly the fixed plan persisted by P2-A and no other skill sequence. */
@Component
public class WorkbenchExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchExecutionEngine.class);

    private final WorkbenchRunTraceService traceService;
    private final PaperLayoutArtifactService artifactService;
    private final SelectionAnchorResolver anchorResolver;
    private final PaperLayoutEvidenceService localEvidenceService;
    private final WorkbenchEvidenceRetrievalService wholePaperEvidenceService;
    private final WorkbenchModelService modelService;
    private final WorkbenchEvidenceGate evidenceGate;
    private final WorkbenchOutputQualityGate outputQualityGate;
    private final WorkbenchAnalysisReportService reportService;
    private final PaperContextAssembler contextAssembler;
    private final PaperMemoryObservationService observationService;
    private final PaperMapper paperMapper;
    private final ObjectMapper objectMapper;

    public WorkbenchExecutionEngine(WorkbenchRunTraceService traceService,
                                    PaperLayoutArtifactService artifactService,
                                    SelectionAnchorResolver anchorResolver,
                                    PaperLayoutEvidenceService localEvidenceService,
                                    WorkbenchEvidenceRetrievalService wholePaperEvidenceService,
                                    WorkbenchModelService modelService,
                                    WorkbenchEvidenceGate evidenceGate,
                                    WorkbenchOutputQualityGate outputQualityGate,
                                    WorkbenchAnalysisReportService reportService,
                                    PaperContextAssembler contextAssembler,
                                    PaperMemoryObservationService observationService,
                                    PaperMapper paperMapper,
                                    ObjectMapper objectMapper) {
        this.traceService = traceService;
        this.artifactService = artifactService;
        this.anchorResolver = anchorResolver;
        this.localEvidenceService = localEvidenceService;
        this.wholePaperEvidenceService = wholePaperEvidenceService;
        this.modelService = modelService;
        this.evidenceGate = evidenceGate;
        this.outputQualityGate = outputQualityGate;
        this.reportService = reportService;
        this.contextAssembler = contextAssembler;
        this.observationService = observationService;
        this.paperMapper = paperMapper;
        this.objectMapper = objectMapper;
    }

    public WorkbenchWorkflowResult execute(String runId, String taskId, Consumer<String> stageUpdater) {
        Consumer<String> stage = stageUpdater == null ? ignored -> { } : stageUpdater;
        WorkbenchRunTrace trace = traceService.prepareExecutionAttempt(runId, taskId);
        if (trace.status() == WorkbenchRunStatus.COMPLETED) {
            WorkbenchWorkflowResult completed = resultFromTrace(trace);
            rememberSelectionSafely(trace, completed);
            return completed;
        }
        try {
            WorkbenchWorkflowResult result = switch (trace.plan().workflow()) {
                case SELECTION_QA, ANNOTATION_SUGGESTION -> executeSelection(trace, stage);
                case PAPER_ANALYSIS -> executePaperAnalysis(trace, stage);
                case PAPER_IMPROVEMENT -> executePaperImprovement(trace, stage);
                case PAPER_COMPARISON, RESEARCH_GAP -> executeComparison(trace, stage);
            };
            traceService.completeRun(runId, result, result.evidence().size());
            rememberSelectionSafely(traceService.requireTrace(runId), result);
            return result;
        } catch (WorkbenchModelException e) {
            failRunIfTerminal(runId, e.code(), e.getMessage(), e.retryable());
            throw new AsyncTaskExecutionException(e.code(), e.getMessage(), e.retryable(), e);
        } catch (StepFailure e) {
            failRunIfTerminal(runId, e.code, e.getMessage(), e.retryable);
            throw new AsyncTaskExecutionException(e.code, e.getMessage(), e.retryable, e);
        } catch (StaleLayoutArtifactException e) {
            failRunIfTerminal(runId, "STALE_LAYOUT_ARTIFACT", "PDF 已更新，请重新选择内容", false);
            throw new AsyncTaskExecutionException(
                    "STALE_LAYOUT_ARTIFACT", "PDF 已更新，请重新选择内容", false, e);
        } catch (RuntimeException e) {
            failRunIfTerminal(runId, "WORKBENCH_EXECUTION_FAILED", "工作台执行失败", false);
            throw new AsyncTaskExecutionException(
                    "WORKBENCH_EXECUTION_FAILED", "工作台执行失败", false, e);
        }
    }

    private WorkbenchWorkflowResult executeSelection(WorkbenchRunTrace trace, Consumer<String> stage) {
        stage.accept("正在解析选区…");
        PaperLayoutArtifact artifact = currentArtifact(trace, trace.invocation().paperIds().get(0));
        SelectionAnchor canonicalAnchor = deterministicStep(
                trace.runId(), 0,
                Map.of("paperId", artifact.paperId(), "page", trace.invocation().selectionAnchor().page()),
                () -> anchorResolver.resolve(
                        artifact,
                        trace.invocation().selectionAnchor().page(),
                        trace.invocation().selectionAnchor().boxes(),
                        trace.invocation().selectionAnchor().anchorText(),
                        trace.invocation().selectionAnchor().kind()),
                anchor -> Map.of("kind", anchor.kind().name(), "confidence", anchor.confidence(),
                        "blockCount", anchor.blockIds().size()));

        stage.accept("正在组装本轮上下文…");
        PaperContextSnapshot context = contextAssembler.assemble(trace, canonicalAnchor);

        stage.accept("正在检索局部证据…");
        LocalEvidenceResult local = deterministicStep(
                trace.runId(), 1,
                Map.of("paperId", artifact.paperId(), "anchorKind", canonicalAnchor.kind().name()),
                () -> localEvidenceService.retrieve(
                        artifact, canonicalAnchor, context.retrievalQuery(), 8),
                result -> Map.of("evidenceCount", result.evidence().size(),
                        "regionFallback", result.regionFallback()));

        stage.accept("正在检索整篇论文的相关证据…");
        List<LayoutEvidence> paperEvidence = wholePaperEvidenceService.retrievePaper(
                artifact, context.retrievalQuery(), 12, 8_000);
        List<LayoutEvidence> combinedEvidence = mergeSelectionEvidence(
                local.evidence(), paperEvidence, 18, 14_000);
        String boundedModelQuestion = context.modelQuestion(selectionModelContextBudget(trace));
        return modelAndGate(trace, combinedEvidence, local.regionFallback(), stage, 2, 3,
                boundedModelQuestion, context);
    }

    private WorkbenchWorkflowResult executePaperAnalysis(WorkbenchRunTrace trace, Consumer<String> stage) {
        Long paperId = trace.invocation().paperIds().get(0);
        stage.accept("正在准备全文版面制品…");
        PaperLayoutArtifact artifact = deterministicStep(
                trace.runId(), 0, Map.of("paperId", paperId),
                () -> currentArtifact(trace, paperId),
                value -> Map.of("pageCount", value.pageCount(), "layoutConfidence", value.layoutConfidence(),
                        "parserVersion", value.parserVersion()));

        stage.accept("正在检索全文证据…");
        List<LayoutEvidence> evidence = deterministicStep(
                trace.runId(), 1, Map.of("paperId", paperId, "maxEvidence", 48),
                () -> wholePaperEvidenceService.retrievePaper(
                        artifact, fullPaperQuery(trace.invocation().question()), 48,
                        evidenceCharacterBudget(trace, 36_000)),
                value -> Map.of("evidenceCount", value.size(), "sectionCount", sectionCount(value)));
        WorkbenchWorkflowResult result = modelAndGate(trace, evidence, false, stage, 2, 3,
                trace.invocation().question(), null);

        stage.accept("正在保存证据化分析…");
        persistenceStep(trace.runId(), 4, () -> reportService.persist(
                paperId, result, trace.artifactVersions().get(0), currentTokenUsage(trace.runId())));
        return result;
    }

    private WorkbenchWorkflowResult executePaperImprovement(WorkbenchRunTrace trace, Consumer<String> stage) {
        Long paperId = trace.invocation().paperIds().get(0);
        stage.accept("正在准备论文版面制品…");
        PaperLayoutArtifact artifact = deterministicStep(
                trace.runId(), 0, Map.of("paperId", paperId),
                () -> currentArtifact(trace, paperId),
                value -> Map.of("pageCount", value.pageCount(), "layoutConfidence", value.layoutConfidence(),
                        "parserVersion", value.parserVersion()));

        stage.accept("正在检索论文局限与改进证据…");
        List<LayoutEvidence> evidence = deterministicStep(
                trace.runId(), 1, Map.of("paperId", paperId, "maxEvidence", 48),
                () -> wholePaperEvidenceService.retrievePaper(
                        artifact, paperImprovementQuery(trace.invocation().question()), 48,
                        evidenceCharacterBudget(trace, 36_000)),
                value -> Map.of("evidenceCount", value.size(), "sectionCount", sectionCount(value)));
        return modelAndGate(trace, evidence, false, stage, 2, 3,
                trace.invocation().question(), null);
    }

    private WorkbenchWorkflowResult executeComparison(WorkbenchRunTrace trace, Consumer<String> stage) {
        stage.accept("正在准备多篇版面制品…");
        List<PaperLayoutArtifact> artifacts = deterministicStep(
                trace.runId(), 0, Map.of("paperCount", trace.invocation().paperIds().size()),
                () -> trace.invocation().paperIds().stream().map(id -> currentArtifact(trace, id)).toList(),
                value -> Map.of("paperCount", value.size(),
                        "minimumLayoutConfidence", value.stream()
                                .mapToDouble(PaperLayoutArtifact::layoutConfidence).min().orElse(0)));

        stage.accept("正在检索分论文证据…");
        List<LayoutEvidence> evidence = deterministicStep(
                trace.runId(), 1, Map.of("paperCount", artifacts.size(), "maxEvidence", 48),
                () -> wholePaperEvidenceService.retrieveComparison(
                        artifacts, trace.invocation().question(), 48,
                        evidenceCharacterBudget(trace, 42_000)),
                value -> Map.of("evidenceCount", value.size(),
                        "representedPapers", value.stream().map(LayoutEvidence::paperId).distinct().count()));
        return modelAndGate(trace, evidence, false, stage, 2, 3,
                trace.invocation().question(), null);
    }

    private WorkbenchWorkflowResult modelAndGate(WorkbenchRunTrace initialTrace,
                                                  List<LayoutEvidence> evidence,
                                                  boolean regionFallback,
                                                  Consumer<String> stage,
                                                  int modelStepIndex,
                                                  int gateStepIndex,
                                                  String modelQuestion,
                                                  PaperContextSnapshot context) {
        if (evidence == null || evidence.isEmpty()) {
            throw new StepFailure("NO_EVIDENCE", "该范围没有可安全引用的论文证据", false);
        }
        regionFallback = regionFallback || evidence.stream().anyMatch(item ->
                item.contentMode() == com.research.assistant.service.pdf.layout.DocumentBlockContentMode.REGION);
        WorkbenchRunTrace trace = traceService.requireTrace(initialTrace.runId());
        if (stepStatus(trace, gateStepIndex) == WorkbenchStepStatus.COMPLETED && trace.result() != null) {
            return resultFromTrace(trace);
        }

        stage.accept("正在基于证据生成回答…");
        WorkbenchModelService.ModelCall call = modelStep(
                trace, modelStepIndex, evidence, null, List.of(), firstCallBudget(trace),
                modelQuestion, context);
        WorkbenchEvidenceGate.GateResult gateResult = gateStep(
                traceService.requireTrace(trace.runId()), gateStepIndex,
                call.output(), call.structured(), evidence, 0);

        if (gateResult.decision() == WorkbenchEvidenceGate.Decision.REPAIR) {
            stage.accept("证据门禁未通过，正在执行唯一一次修复…");
            traceService.prepareSingleRepair(trace.runId(), modelStepIndex, gateStepIndex);
            WorkbenchRunTrace repairTrace = traceService.requireTrace(trace.runId());
            WorkbenchModelService.ModelCall repaired = modelStep(
                    repairTrace, modelStepIndex, evidence, call.output(), gateResult.issues(),
                    remainingTokenBudget(repairTrace), modelQuestion, context);
            gateResult = gateStep(
                    traceService.requireTrace(trace.runId()), gateStepIndex,
                    repaired.output(), repaired.structured(), evidence, 1);
            call = repaired;
        }
        if (gateResult.decision() != WorkbenchEvidenceGate.Decision.PASS) {
            throw new StepFailure("EVIDENCE_GATE_REJECTED", "回答缺少可验证证据", false);
        }

        WorkbenchModelOutput output = call.output().normalizedFor(trace.plan().workflow());
        WorkbenchRunTrace passedTrace = traceService.requireTrace(trace.runId());
        WorkbenchWorkflowResult result = new WorkbenchWorkflowResult(
                trace.runId(), trace.plan().workflow(), trace.plan().scope(), trace.invocation().paperIds(),
                output.answer(), output.claims(), evidence, output.annotationSuggestion(), regionFallback,
                passedTrace.metrics().repairCount());
        traceService.checkpointResult(trace.runId(), result);
        return result;
    }

    private WorkbenchModelService.ModelCall modelStep(WorkbenchRunTrace trace,
                                                       int stepIndex,
                                                       List<LayoutEvidence> evidence,
                                                       WorkbenchModelOutput previous,
                                                       List<String> repairIssues,
                                                       int callBudget,
                                                       String modelQuestion,
                                                       PaperContextSnapshot context) {
        Map<String, Object> inputSummary = new LinkedHashMap<>();
        inputSummary.put("evidenceCount", evidence.size());
        inputSummary.put("callTokenBudget", callBudget);
        inputSummary.put("repair", previous != null);
        if (context != null) {
            inputSummary.put("contextSchemaVersion", context.schemaVersion());
            inputSummary.put("conversationTurns", context.conversationTurns().size());
            inputSummary.put("memoryObservations", context.relevantObservations().size());
            inputSummary.put("profileIncluded", !context.profileContext().isBlank());
            inputSummary.put("contextCharacters", context.budget().usedCharacters());
            inputSummary.put("modelContextCharacters", modelQuestion.length());
            inputSummary.put("contextTruncated", context.truncated());
            inputSummary.put("sourcePriority", context.sourcePriority());
        }
        traceService.startStep(trace.runId(), stepIndex, inputSummary);
        long started = System.nanoTime();
        try {
            WorkbenchModelService.ModelCall call = modelService.generate(
                    trace.plan().workflow(), modelQuestion, paperTitles(trace.invocation().paperIds()),
                    evidence, callBudget, previous, repairIssues);
            traceService.completeStep(trace.runId(), stepIndex,
                    modelSuccessSummary(call),
                    evidence.size(), call.promptTokens(), call.completionTokens(), elapsed(started));
            return call;
        } catch (WorkbenchModelException e) {
            traceService.failStep(trace.runId(), stepIndex, e.code(), e.getMessage(),
                    modelFailureSummary(e), e.promptTokens(), e.completionTokens(), elapsed(started));
            throw e;
        }
    }

    private List<LayoutEvidence> mergeSelectionEvidence(List<LayoutEvidence> local,
                                                        List<LayoutEvidence> paper,
                                                        int maxEvidence,
                                                        int maxCharacters) {
        Map<String, LayoutEvidence> merged = new LinkedHashMap<>();
        if (local != null) {
            local.forEach(item -> merged.putIfAbsent(item.evidenceId(), item));
        }
        if (paper != null) {
            paper.forEach(item -> merged.putIfAbsent(item.evidenceId(), item));
        }
        List<LayoutEvidence> result = new ArrayList<>();
        int characters = 0;
        for (LayoutEvidence item : merged.values()) {
            if (result.size() >= maxEvidence) break;
            int next = characters + safeLength(item.text()) + safeLength(item.structuredContent());
            if (!result.isEmpty() && next > maxCharacters && !item.selected()) continue;
            result.add(item);
            characters = next;
        }
        return List.copyOf(result);
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private int selectionModelContextBudget(WorkbenchRunTrace trace) {
        return Math.max(1_200, Math.min(2_000, trace.plan().tokenBudget() / 5));
    }

    private Map<String, Object> modelSuccessSummary(WorkbenchModelService.ModelCall call) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("structured", call.structured());
        summary.put("claimCount", call.output().claims().size());
        summary.put("answerCharacters", call.output().answer().length());
        summary.put("attemptCount", call.attemptCount());
        summary.put("emptyOutputRecoveryUsed", call.recoveryUsed());
        if (call.finishReason() != null && !call.finishReason().isBlank()) {
            summary.put("finishReason", call.finishReason());
        }
        return summary;
    }

    private Map<String, Object> modelFailureSummary(WorkbenchModelException error) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("attemptCount", error.attemptCount());
        summary.put("emptyOutputRecoveryUsed", error.attemptCount() > 1);
        if (error.finishReason() != null && !error.finishReason().isBlank()) {
            summary.put("finishReason", error.finishReason());
        }
        return summary;
    }

    private WorkbenchEvidenceGate.GateResult gateStep(WorkbenchRunTrace trace,
                                                       int stepIndex,
                                                       WorkbenchModelOutput output,
                                                       boolean structured,
                                                       List<LayoutEvidence> evidence,
                                                       int repairAttempt) {
        traceService.startStep(trace.runId(), stepIndex,
                Map.of("candidateEvidenceCount", evidence.size(), "repairAttempt", repairAttempt));
        long started = System.nanoTime();
        WorkbenchEvidenceGate.GateResult evidenceResult = evidenceGate.validate(
                output.toGateDraft(trace.plan().workflow()), evidence, gatePolicy(trace, repairAttempt));
        List<String> issues = new ArrayList<>(evidenceResult.issues());
        issues.addAll(outputQualityGate.validate(
                trace.plan().workflow(), output.normalizedFor(trace.plan().workflow()), structured,
                trace.invocation().paperIds().size()));
        WorkbenchEvidenceGate.Decision decision = issues.isEmpty()
                ? WorkbenchEvidenceGate.Decision.PASS
                : repairAttempt < trace.plan().repairLimit()
                ? WorkbenchEvidenceGate.Decision.REPAIR : WorkbenchEvidenceGate.Decision.REJECT;
        WorkbenchEvidenceGate.GateResult result = new WorkbenchEvidenceGate.GateResult(
                decision, evidenceResult.claimCoverage(), evidenceResult.validEvidenceIds(),
                evidenceResult.invalidEvidenceIds(), List.copyOf(issues));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("decision", result.decision().name());
        summary.put("claimCoverage", result.claimCoverage());
        summary.put("validEvidenceCount", result.validEvidenceIds().size());
        summary.put("invalidEvidenceCount", result.invalidEvidenceIds().size());
        summary.put("issues", result.issues());
        if (result.decision() == WorkbenchEvidenceGate.Decision.PASS) {
            traceService.completeStep(trace.runId(), stepIndex, summary,
                    result.validEvidenceIds().size(), 0, 0, elapsed(started));
        } else {
            traceService.failStep(trace.runId(), stepIndex,
                    result.decision() == WorkbenchEvidenceGate.Decision.REPAIR
                            ? "EVIDENCE_REPAIR_REQUIRED" : "EVIDENCE_GATE_REJECTED",
                    "证据门禁未通过", elapsed(started));
        }
        return result;
    }

    private WorkbenchEvidenceGate.GatePolicy gatePolicy(WorkbenchRunTrace trace, int repairAttempt) {
        return switch (trace.plan().workflow()) {
            case SELECTION_QA, ANNOTATION_SUGGESTION ->
                    WorkbenchEvidenceGate.GatePolicy.selection(repairAttempt);
            case PAPER_COMPARISON, RESEARCH_GAP -> WorkbenchEvidenceGate.GatePolicy.comparison(
                    repairAttempt, Set.copyOf(trace.invocation().paperIds()));
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT -> WorkbenchEvidenceGate.GatePolicy.strict(repairAttempt);
        };
    }

    private <T> T deterministicStep(String runId,
                                    int stepIndex,
                                    Object inputSummary,
                                    Supplier<T> action,
                                    Function<T, Object> outputSummary) {
        WorkbenchStepStatus status = stepStatus(traceService.requireTrace(runId), stepIndex);
        if (status == WorkbenchStepStatus.COMPLETED) return action.get();
        if (status != WorkbenchStepStatus.PENDING) {
            throw new StepFailure("INVALID_STEP_STATE", "工作台步骤状态异常", false);
        }
        traceService.startStep(runId, stepIndex, inputSummary);
        long started = System.nanoTime();
        try {
            T result = action.get();
            traceService.completeStep(runId, stepIndex, outputSummary.apply(result), 0, 0, 0, elapsed(started));
            return result;
        } catch (StaleLayoutArtifactException e) {
            traceService.failStep(runId, stepIndex, "STALE_LAYOUT_ARTIFACT",
                    "PDF 已更新，请重新选择内容", elapsed(started));
            throw e;
        } catch (RuntimeException e) {
            traceService.failStep(runId, stepIndex, "DETERMINISTIC_STEP_FAILED",
                    "确定性工作台步骤失败", elapsed(started));
            throw new StepFailure("DETERMINISTIC_STEP_FAILED", "确定性工作台步骤失败", false, e);
        }
    }

    private void persistenceStep(String runId, int stepIndex, Runnable action) {
        WorkbenchStepStatus status = stepStatus(traceService.requireTrace(runId), stepIndex);
        if (status == WorkbenchStepStatus.COMPLETED) return;
        traceService.startStep(runId, stepIndex, Map.of("operation", "persist-grounded-report"));
        long started = System.nanoTime();
        try {
            action.run();
            traceService.completeStep(runId, stepIndex, Map.of("persisted", true), 0, 0, 0, elapsed(started));
        } catch (DataAccessException e) {
            traceService.failStep(runId, stepIndex, "REPORT_PERSISTENCE_FAILED",
                    "分析报告暂时无法保存", elapsed(started));
            throw new StepFailure("REPORT_PERSISTENCE_FAILED", "分析报告暂时无法保存", true, e);
        } catch (RuntimeException e) {
            traceService.failStep(runId, stepIndex, "REPORT_INVALID",
                    "分析报告无法保存", elapsed(started));
            throw new StepFailure("REPORT_INVALID", "分析报告无法保存", false, e);
        }
    }

    private PaperLayoutArtifact currentArtifact(WorkbenchRunTrace trace, Long paperId) {
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        WorkbenchPlan.ArtifactVersion expected = trace.artifactVersions().stream()
                .filter(version -> version.paperId().equals(paperId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("run is missing an artifact version"));
        if (!expected.documentHash().equals(artifact.documentHash())
                || !expected.parserVersion().equals(artifact.parserVersion())) {
            throw new StaleLayoutArtifactException();
        }
        return artifact;
    }

    private Map<Long, String> paperTitles(List<Long> paperIds) {
        Map<Long, String> titles = new LinkedHashMap<>();
        for (Long paperId : paperIds) {
            Paper paper = paperMapper.selectById(paperId);
            titles.put(paperId, paper == null || paper.getTitle() == null ? "Paper " + paperId : paper.getTitle());
        }
        return titles;
    }

    private String fullPaperQuery(String question) {
        return question == null || question.isBlank()
                ? "research question method contribution experiment result limitation reproducibility"
                : question;
    }

    private String paperImprovementQuery(String question) {
        String focus = "limitation weakness assumption boundary future work method data scenario metric experiment reproducibility";
        return question == null || question.isBlank() ? focus : question + " " + focus;
    }

    private int sectionCount(List<LayoutEvidence> evidence) {
        return (int) evidence.stream().map(item -> String.join(" / ", item.sectionPath())).distinct().count();
    }

    private int firstCallBudget(WorkbenchRunTrace trace) {
        int remaining = remainingTokenBudget(trace);
        if (remaining < 512) return remaining;
        if (trace.plan().workflow() == WorkbenchPlan.Workflow.SELECTION_QA
                || trace.plan().workflow() == WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION) {
            return remaining;
        }
        int numerator = switch (trace.plan().workflow()) {
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT -> 6;
            case PAPER_COMPARISON, RESEARCH_GAP -> 4;
            case SELECTION_QA, ANNOTATION_SUGGESTION -> 1;
        };
        int denominator = switch (trace.plan().workflow()) {
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT -> 7;
            case PAPER_COMPARISON, RESEARCH_GAP -> 5;
            case SELECTION_QA, ANNOTATION_SUGGESTION -> 1;
        };
        return Math.max(512, remaining * numerator / denominator);
    }

    private int remainingTokenBudget(WorkbenchRunTrace trace) {
        int used = trace.steps().stream().mapToInt(WorkbenchRunTrace.StepTrace::totalTokens).sum();
        return Math.max(0, trace.plan().tokenBudget() - used);
    }

    /** Leaves room for the system prompt, JSON envelope, answer and the single permitted repair. */
    private int evidenceCharacterBudget(WorkbenchRunTrace trace, int ceiling) {
        long budget = switch (trace.plan().workflow()) {
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT ->
                    Math.max(6_000L, (long) trace.plan().tokenBudget() * 3 / 4);
            case PAPER_COMPARISON, RESEARCH_GAP -> Math.max(8_000L, (long) trace.plan().tokenBudget());
            case SELECTION_QA, ANNOTATION_SUGGESTION ->
                    Math.max(4_000L, (long) trace.plan().tokenBudget() * 5 / 4);
        };
        return (int) Math.min(ceiling, budget);
    }

    private int currentTokenUsage(String runId) {
        return traceService.requireTrace(runId).steps().stream()
                .mapToInt(WorkbenchRunTrace.StepTrace::totalTokens).sum();
    }

    private WorkbenchStepStatus stepStatus(WorkbenchRunTrace trace, int index) {
        return trace.steps().stream().filter(step -> step.index() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workbench step is missing"))
                .status();
    }

    private WorkbenchWorkflowResult resultFromTrace(WorkbenchRunTrace trace) {
        if (trace.result() == null) throw new IllegalStateException("completed run has no result");
        return objectMapper.convertValue(trace.result(), WorkbenchWorkflowResult.class);
    }

    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    private void failRunIfTerminal(String runId, String code, String message, boolean retryable) {
        if (retryable) return;
        WorkbenchRunStatus status = traceService.requireTrace(runId).status();
        if (status != WorkbenchRunStatus.COMPLETED && status != WorkbenchRunStatus.FAILED
                && status != WorkbenchRunStatus.CANCELLED) {
            traceService.failRun(runId, code, message);
        }
    }

    private void rememberSelectionSafely(WorkbenchRunTrace trace, WorkbenchWorkflowResult result) {
        if (result.workflow() != WorkbenchPlan.Workflow.SELECTION_QA) return;
        try {
            observationService.remember(trace, result);
        } catch (RuntimeException memoryError) {
            log.warn("paper_observation_update_failed runId={} errorType={}",
                    trace.runId(), memoryError.getClass().getSimpleName());
        }
    }

    private static class StepFailure extends RuntimeException {
        private final String code;
        private final boolean retryable;

        StepFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        StepFailure(String code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }
    }
}
