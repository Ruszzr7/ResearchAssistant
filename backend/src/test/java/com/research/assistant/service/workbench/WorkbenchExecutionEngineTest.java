package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.PaperWorkbenchStepMapper;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.memory.PaperMemoryObservationService;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.TransientDataAccessResourceException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkbenchExecutionEngineTest {

    @Autowired private PaperWorkbenchRunMapper runMapper;
    @Autowired private PaperWorkbenchStepMapper stepMapper;
    @Autowired private ObjectMapper objectMapper;

    private PaperLayoutArtifactService artifactService;
    private SelectionAnchorResolver anchorResolver;
    private PaperLayoutEvidenceService localEvidenceService;
    private WorkbenchEvidenceRetrievalService wholeEvidenceService;
    private WorkbenchModelService modelService;
    private WorkbenchAnalysisReportService reportService;
    private PaperContextAssembler contextAssembler;
    private PaperMemoryObservationService observationService;
    private PaperMapper paperMapper;
    private WorkbenchSelectionVisualEvidenceService visualEvidenceService;
    private WorkbenchRunTraceService traceService;
    private WorkbenchExecutionEngine engine;

    @BeforeEach
    void setUp() {
        artifactService = mock(PaperLayoutArtifactService.class);
        anchorResolver = mock(SelectionAnchorResolver.class);
        localEvidenceService = mock(PaperLayoutEvidenceService.class);
        wholeEvidenceService = mock(WorkbenchEvidenceRetrievalService.class);
        modelService = mock(WorkbenchModelService.class);
        reportService = mock(WorkbenchAnalysisReportService.class);
        contextAssembler = mock(PaperContextAssembler.class);
        observationService = mock(PaperMemoryObservationService.class);
        paperMapper = mock(PaperMapper.class);
        visualEvidenceService = mock(WorkbenchSelectionVisualEvidenceService.class);

        when(artifactService.ensureArtifact(anyLong(), eq(false)))
                .thenAnswer(invocation -> artifact(invocation.getArgument(0)));
        when(anchorResolver.resolve(any(), anyInt(), anyList(), anyString(), any()))
                .thenAnswer(invocation -> anchor(7L));
        when(localEvidenceService.retrieve(any(), any(), anyString(), anyInt()))
                .thenReturn(new LocalEvidenceResult(anchor(7L), List.of(evidence(7L, true)), false));
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(evidence(7L, false)));
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(evidence(7L, false)));
        when(wholeEvidenceService.retrieveComparison(anyList(), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> ((List<PaperLayoutArtifact>) invocation.getArgument(0)).stream()
                        .map(artifact -> evidence(artifact.paperId(), false)).toList());
        when(paperMapper.selectById(anyLong())).thenAnswer(invocation -> paper(invocation.getArgument(0)));
        when(modelService.generate(any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList()))
                .thenAnswer(invocation -> modelCall(invocation.getArgument(0)));
        when(contextAssembler.assemble(any(), any())).thenAnswer(invocation ->
                context(invocation.getArgument(0)));

        traceService = new WorkbenchRunTraceService(
                runMapper, stepMapper, new WorkbenchRuleRouter(), artifactService, objectMapper);
        engine = new WorkbenchExecutionEngine(
                traceService, artifactService, anchorResolver, localEvidenceService, wholeEvidenceService,
                modelService, new WorkbenchEvidenceGate(), new WorkbenchOutputQualityGate(), reportService,
                contextAssembler, observationService, paperMapper, objectMapper, visualEvidenceService,
                new WorkbenchEvidencePackager());
    }

    @Test
    void executesSelectionQuestionAndAnnotationSuggestion() {
        WorkbenchRunTrace selection = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));
        WorkbenchWorkflowResult selectionResult = engine.execute(selection.runId(), "task-selection", null);

        assertThat(selectionResult.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(selectionResult.evidence()).singleElement().satisfies(item -> assertThat(item.selected()).isTrue());
        verify(observationService).remember(any(), eq(selectionResult));
        assertCompleted(selection.runId(), 4);

        WorkbenchRunTrace annotation = traceService.plan(invocation(
                WorkbenchIntent.SUGGEST_ANNOTATION, List.of(7L), anchor(7L), "生成批注", 6_000));
        WorkbenchWorkflowResult annotationResult = engine.execute(annotation.runId(), "task-annotation", null);

        assertThat(annotationResult.workflow()).isEqualTo(WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION);
        assertThat(annotationResult.annotationSuggestion()).isNotNull();
        assertThat(annotationResult.annotationSuggestion().evidenceIds()).containsExactly("lay_p7");
        assertCompleted(annotation.runId(), 4);
    }

    @Test
    void completedSelectionReplayReconcilesMemoryWithoutCallingTheModelAgain() {
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));

        WorkbenchWorkflowResult first = engine.execute(planned.runId(), "task-selection-replay", null);
        WorkbenchWorkflowResult replayed = engine.execute(planned.runId(), "task-selection-replay", null);

        assertThat(replayed).isEqualTo(first);
        verify(observationService, times(2)).remember(any(), eq(first));
        verify(modelService, times(1)).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
    }

    @Test
    void appendsDeterministicWarningWhenTheConfiguredModelCannotUseTheSelectionImage() {
        WorkbenchSelectionVisualEvidence visual = new WorkbenchSelectionVisualEvidence(
                new byte[0], 1, new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.05),
                "selected math", "图像不可用");
        when(visualEvidenceService.create(any())).thenReturn(visual);
        WorkbenchModelService.ModelCall base = modelCall(WorkbenchPlan.Workflow.SELECTION_QA);
        when(modelService.generate(any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList(),
                eq(visual))).thenReturn(new WorkbenchModelService.ModelCall(
                base.output(), base.structured(), base.promptTokens(), base.completionTokens(),
                base.totalTokens(), base.finishReason(), base.attemptCount(), base.recoveryUsed(),
                false, true));

        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));
        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-visual-fallback", null);

        assertThat(result.answer()).contains("当前模型未接受选区图像", "需回原页核对");
    }

    @Test
    void selectionFollowUpUsesTheAnchorHistoryAndWholePaperRetrieval() {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "它和全文实验结果有什么关系？", WorkbenchIntent.ASK_SELECTION,
                null, anchor(7L), 6, 10_000, "", "selection-thread_1");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        engine.execute(planned.runId(), "task-selection-follow-up", null);

        ArgumentCaptor<String> retrievalQuery = ArgumentCaptor.forClass(String.class);
        verify(wholeEvidenceService).retrievePaper(
                any(), retrievalQuery.capture(), eq(List.of("p1-b0001")), eq(12), eq(8_000));
        assertThat(retrievalQuery.getValue())
                .contains("它和全文实验结果有什么关系？", "当前选区：selected", "历史追问：");

        ArgumentCaptor<String> modelQuestion = ArgumentCaptor.forClass(String.class);
        verify(modelService).generate(eq(WorkbenchPlan.Workflow.SELECTION_QA), modelQuestion.capture(),
                anyMap(), anyList(), anyInt(), any(), anyList());
        assertThat(modelQuestion.getValue())
                .contains("同一论文与同一对话的服务端历史", "它处理有限块长可靠性",
                        "当前问题：它和全文实验结果有什么关系？");
        assertThat(traceService.requireTrace(planned.runId()).steps().get(2).inputSummary().toString())
                .contains(PaperContextSnapshot.SCHEMA_VERSION, "conversationTurns=1", "sourcePriority",
                        "modelContextCharacters");
    }

    @Test
    void conversationWithoutSelectionUsesIndependentHistoryAndWholePaperEvidence() {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "继续说明论文的主要贡献", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "paper-thread_1");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        WorkbenchWorkflowResult result = engine.execute(
                planned.runId(), "task-paper-conversation", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(result.scope()).isEqualTo(WorkbenchPlan.Scope.PAPER);
        verify(anchorResolver, times(0)).resolve(any(), anyInt(), anyList(), anyString(), any());
        verify(localEvidenceService, times(0)).retrieve(any(), any(), anyString(), anyInt());
        verify(wholeEvidenceService).retrievePaper(
                any(), anyString(), eq(List.of("p1-b0001")), eq(18), eq(14_000));
        verify(contextAssembler).assemble(any(), org.mockito.ArgumentMatchers.isNull());
        assertCompleted(planned.runId(), 4);
    }

    @Test
    void executesWholePaperAnalysisAndPersistsOnlyAfterGate() {
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ANALYZE_PAPER, List.of(7L), null, "", 14_000));

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-analysis", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_ANALYSIS);
        assertCompleted(planned.runId(), 5);
        verify(reportService).persist(eq(7L), eq(result), any(), eq(15));
    }

    @Test
    void executesSinglePaperImprovementWithoutExpandingToAFieldClaim() {
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.IDENTIFY_PAPER_IMPROVEMENTS, List.of(7L), null,
                "分析论文改进空间与可检验研究切入点", 14_000));

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-improvement", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_IMPROVEMENT);
        assertThat(result.paperIds()).containsExactly(7L);
        assertCompleted(planned.runId(), 4);
        verify(reportService, times(0)).persist(anyLong(), any(), any(), anyInt());
    }

    @Test
    void executesComparisonOnlyWhenEveryPaperIsCited() {
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.COMPARE_PAPERS, List.of(7L, 8L), null, "比较方法与局限", 20_000));

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-comparison", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_COMPARISON);
        assertThat(result.claims()).extracting(claim -> claim.evidenceIds().get(0))
                .containsExactly("lay_p7", "lay_p8");
        assertCompleted(planned.runId(), 4);
    }

    @Test
    void executesResearchGapOnlyWhenEveryPaperIsCited() {
        WorkbenchRunTrace comparison = traceService.plan(invocation(
                WorkbenchIntent.COMPARE_PAPERS, List.of(7L, 8L, 9L), null,
                "先完成跨论文对比", 20_000));
        completeSourceComparison(comparison);
        WorkbenchRunTrace planned = traceService.plan(new WorkbenchInvocation(
                List.of(7L, 8L, 9L), "识别可检验且仍需验证的候选研究空白",
                WorkbenchIntent.FIND_RESEARCH_GAPS, null, null, 6, 20_000, comparison.runId()));

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-gap", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.RESEARCH_GAP);
        assertThat(result.claims()).extracting(claim -> claim.evidenceIds().get(0))
                .containsExactly("lay_p7", "lay_p8", "lay_p9");
        assertCompleted(planned.runId(), 4);
    }

    private void completeSourceComparison(WorkbenchRunTrace comparison) {
        traceService.startRun(comparison.runId(), "task-source-comparison");
        for (int index = 0; index < comparison.steps().size(); index++) {
            traceService.startStep(comparison.runId(), index, null);
            traceService.completeStep(comparison.runId(), index, null, 0, 0, 0, 1);
        }
        traceService.completeRun(comparison.runId(), java.util.Map.of("answer", "completed comparison"), 0);
    }

    @Test
    void malformedFirstOutputUsesExactlyOneGroundedRepair() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> calls.getAndIncrement() == 0
                        ? new WorkbenchModelService.ModelCall(
                        new WorkbenchModelOutput("not-json", List.of(), null), false, 10, 5, 15)
                        : modelCall(WorkbenchPlan.Workflow.SELECTION_QA))
                .when(modelService).generate(
                        any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-repair", null);
        WorkbenchRunTrace trace = traceService.requireTrace(planned.runId());

        assertThat(result.repairCount()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(trace.metrics().repairCount()).isEqualTo(1);
        assertThat(trace.metrics().totalTokens()).isEqualTo(30);
        assertThat(trace.steps().get(2).retryCount()).isEqualTo(1);
    }

    @Test
    void retriesTransientModelFailureWithoutReplanningTheRun() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new WorkbenchModelException("MODEL_CALL_FAILED", "模型服务暂时不可用", true,
                        70, 50, 120, "LENGTH", 1);
            }
            return modelCall(WorkbenchPlan.Workflow.SELECTION_QA);
        }).when(modelService).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));

        assertThatThrownBy(() -> engine.execute(planned.runId(), "task-model-retry", null))
                .isInstanceOf(AsyncTaskExecutionException.class)
                .satisfies(error -> assertThat(((AsyncTaskExecutionException) error).isRetryable()).isTrue());
        WorkbenchRunTrace failedAttempt = traceService.requireTrace(planned.runId());
        assertThat(failedAttempt.status()).isEqualTo(WorkbenchRunStatus.RUNNING);
        assertThat(failedAttempt.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.FAILED);
        assertThat(failedAttempt.steps().get(2).totalTokens()).isEqualTo(120);
        assertThat(failedAttempt.steps().get(2).outputSummary().toString()).contains("LENGTH");

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-model-retry", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(calls).hasValue(2);
        WorkbenchRunTrace completed = traceService.requireTrace(planned.runId());
        assertThat(completed.steps().get(2).retryCount()).isEqualTo(1);
        assertThat(completed.steps().get(2).totalTokens()).isEqualTo(135);
    }

    @Test
    void persistenceRetryUsesApprovedCheckpointWithoutSecondModelCall() {
        doThrow(new TransientDataAccessResourceException("temporary database failure"))
                .doNothing()
                .when(reportService).persist(anyLong(), any(), any(), anyInt());
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ANALYZE_PAPER, List.of(7L), null, "", 14_000));

        assertThatThrownBy(() -> engine.execute(planned.runId(), "task-persist-retry", null))
                .isInstanceOf(AsyncTaskExecutionException.class)
                .satisfies(error -> assertThat(((AsyncTaskExecutionException) error).isRetryable()).isTrue());
        WorkbenchRunTrace failedAttempt = traceService.requireTrace(planned.runId());
        assertThat(failedAttempt.result()).isNotNull();
        assertThat(failedAttempt.steps().get(3).status()).isEqualTo(WorkbenchStepStatus.COMPLETED);
        assertThat(failedAttempt.steps().get(4).status()).isEqualTo(WorkbenchStepStatus.FAILED);

        WorkbenchWorkflowResult result = engine.execute(planned.runId(), "task-persist-retry", null);

        assertThat(result.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_ANALYSIS);
        assertThat(traceService.requireTrace(planned.runId()).status()).isEqualTo(WorkbenchRunStatus.COMPLETED);
        verify(modelService, times(1)).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        verify(reportService, times(2)).persist(anyLong(), any(), any(), anyInt());
    }

    private WorkbenchInvocation invocation(WorkbenchIntent intent,
                                           List<Long> paperIds,
                                           SelectionAnchor anchor,
                                           String question,
                                           int tokenBudget) {
        return new WorkbenchInvocation(paperIds, question, intent, null, anchor, 6, tokenBudget);
    }

    private PaperContextSnapshot context(WorkbenchRunTrace trace) {
        WorkbenchPlan.ArtifactVersion version = trace.artifactVersions().get(0);
        List<PaperContextSnapshot.ConversationItem> turns = trace.invocation().conversationId().isBlank()
                ? List.of()
                : List.of(new PaperContextSnapshot.ConversationItem(
                        1, "这段方法解决什么问题？", "它处理有限块长可靠性。",
                        List.of("p1-b0001")));
        return new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, version.paperId(), version.documentHash(),
                version.parserVersion(), trace.invocation().conversationId(), trace.invocation().question(),
                trace.invocation().selectionAnchor() == null ? "" : "selected",
                trace.invocation().selectionAnchor() == null ? List.of() : List.of("p1-b0001"),
                PaperContextSnapshot.selectionFingerprint(trace.invocation().selectionAnchor()), "", turns, List.of(),
                List.of("CURRENT_QUESTION", "CURRENT_SELECTION_EVIDENCE"),
                new PaperContextSnapshot.Budget(8_000, 8, 30, 0, 0), false, Instant.now());
    }

    private WorkbenchModelService.ModelCall modelCall(WorkbenchPlan.Workflow workflow) {
        WorkbenchModelOutput output = switch (workflow) {
            case SELECTION_QA -> new WorkbenchModelOutput(
                    "所选段落说明该方法在有限块长条件下兼顾可靠性与时延。",
                    List.of(new WorkbenchEvidenceGate.GroundedClaim("有限块长下兼顾可靠性与时延", List.of("lay_p7"))),
                    null);
            case ANNOTATION_SUGGESTION -> new WorkbenchModelOutput(
                    "这段话强调有限块长条件下的可靠性与时延权衡。",
                    List.of(new WorkbenchEvidenceGate.GroundedClaim("存在可靠性与时延权衡", List.of("lay_p7"))),
                    new WorkbenchModelOutput.AnnotationSuggestion(
                            WorkbenchModelOutput.AnnotationType.COMMENT,
                            "注意：这里的核心是有限块长下可靠性与时延的权衡。", List.of("lay_p7")));
            case PAPER_ANALYSIS -> new WorkbenchModelOutput(
                    ("## 研究问题\n论文研究低时延通信。\n## 方法\n采用有限块长分析与优化方法。\n"
                            + "## 核心贡献\n建立可计算模型并给出优化策略。\n## 主要结果\n结果表明方案改善性能。\n"
                            + "## 局限\n证据仅覆盖论文给出的设定，外部泛化仍有限。\n").repeat(2),
                    List.of(
                            new WorkbenchEvidenceGate.GroundedClaim("论文研究低时延通信", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("采用有限块长分析方法", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("论文给出优化策略", List.of("lay_p7"))),
                    null);
            case PAPER_IMPROVEMENT -> new WorkbenchModelOutput(
                    ("## 改进空间与研究切入点\n论文当前假设边界限制了跨场景泛化。"
                            + "可通过扩展数据场景和对照实验验证改进方向，并报告可复现设置。\n").repeat(4),
                    List.of(
                            new WorkbenchEvidenceGate.GroundedClaim("假设边界可扩展", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("实验场景可补充", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("可复现性可验证", List.of("lay_p7"))),
                    null);
            case PAPER_COMPARISON -> new WorkbenchModelOutput(
                    ("| 论文 | 方法 | 局限 |\n|---|---|---|\n| Paper 7 | 方法 A | 局限 A |\n"
                            + "| Paper 8 | 方法 B | 局限 B |\n两篇论文采用不同方法解决相关问题，假设和适用范围不同。").repeat(2),
                    List.of(
                            new WorkbenchEvidenceGate.GroundedClaim("Paper 7 使用方法 A", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("Paper 8 使用方法 B", List.of("lay_p8"))),
                    null);
            case RESEARCH_GAP -> new WorkbenchModelOutput(
                    ("## 候选空白 1\n三篇论文在假设、优化目标和验证场景上存在尚待验证的覆盖边界。"
                            + "该候选研究空白不能仅凭当前证据断言领域中不存在相关工作。\n"
                            + "## 可检验问题\n可在统一数据与约束下检验跨场景泛化。\n"
                            + "## 下一步验证\n需要扩大外部检索范围，并通过对照实验验证候选空白。\n").repeat(2),
                    List.of(
                            new WorkbenchEvidenceGate.GroundedClaim("Paper 7 的方法边界", List.of("lay_p7")),
                            new WorkbenchEvidenceGate.GroundedClaim("Paper 8 的验证边界", List.of("lay_p8")),
                            new WorkbenchEvidenceGate.GroundedClaim("Paper 9 的场景边界", List.of("lay_p9"))),
                    null);
        };
        return new WorkbenchModelService.ModelCall(output, true, 10, 5, 15);
    }

    private void assertCompleted(String runId, int stepCount) {
        WorkbenchRunTrace trace = traceService.requireTrace(runId);
        assertThat(trace.status()).isEqualTo(WorkbenchRunStatus.COMPLETED);
        assertThat(trace.steps()).hasSize(stepCount)
                .allMatch(step -> step.status() == WorkbenchStepStatus.COMPLETED);
    }

    private SelectionAnchor anchor(Long paperId) {
        return new SelectionAnchor(paperId, 1,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.05)),
                "selected", List.of("p1-b0001"), null, SelectionAnchorKind.TEXT,
                0.9, hash(paperId), "parser-v1");
    }

    private LayoutEvidence evidence(Long paperId, boolean selected) {
        return new LayoutEvidence("lay_p" + paperId, paperId, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.05), DocumentBlockRole.BODY,
                1, List.of("Introduction"), "Evidence for paper " + paperId,
                0.9, selected, 0.9, hash(paperId), "parser-v1");
    }

    private PaperLayoutArtifact artifact(Long paperId) {
        return new PaperLayoutArtifact(paperId, hash(paperId), "parser-v1", 0.9,
                Instant.parse("2026-07-16T00:00:00Z"), 10, List.of());
    }

    private Paper paper(Long paperId) {
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setTitle("Paper " + paperId);
        return paper;
    }

    private String hash(Long paperId) {
        return Long.toHexString(paperId).repeat(64).substring(0, 64);
    }
}
