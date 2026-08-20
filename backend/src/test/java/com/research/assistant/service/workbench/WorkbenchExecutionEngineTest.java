package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.PaperWorkbenchStepMapper;
import com.research.assistant.service.async.AsyncTaskExecutionException;
import com.research.assistant.service.memory.PaperMemoryObservationService;
import com.research.assistant.service.memory.PaperConversationTurn;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import com.research.assistant.service.research.ResearchSessionService;
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
import static org.mockito.Mockito.doReturn;
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
    private ResearchSessionService researchSessionService;
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
        researchSessionService = mock(ResearchSessionService.class);

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
                new WorkbenchEvidencePackager(), new WorkbenchCommandPlanner(), researchSessionService);
    }

    @Test
    void executesSelectionQuestionAndAnnotationSuggestion() {
        WorkbenchRunTrace selection = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));
        WorkbenchWorkflowResult selectionResult = engine.execute(selection.runId(), "task-selection", null);

        assertThat(selectionResult.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(selectionResult.evidence()).singleElement().satisfies(item -> assertThat(item.selected()).isTrue());
        verify(observationService).remember(any(), eq(selectionResult));
        verify(researchSessionService).archiveWorkbenchCompletion(any(), eq(selectionResult));
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
        when(visualEvidenceService.create(any(), anyList())).thenReturn(visual);
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
                any(), anyString(), eq(List.of("p1-b0001")), eq(12), eq(10_000));
        verify(contextAssembler).assemble(any(), org.mockito.ArgumentMatchers.isNull());
        assertCompleted(planned.runId(), 4);
    }

    @Test
    void conversationWithoutSelectionCanAnswerOrdinaryQuestionsWithoutPaperEvidence() {
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "快速排序的平均时间复杂度是 O(n log n)。",
                WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE, List.of(), List.of("r1"));
        WorkbenchModelOutput output = new WorkbenchModelOutput(
                block.text(), List.of(), null, List.of(block), List.of());
        doReturn(new WorkbenchModelService.ModelCall(output, true, 10, 5, 15))
                .when(modelService).generate(eq(WorkbenchPlan.Workflow.SELECTION_QA), anyString(),
                        anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "快速排序的复杂度是什么？", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "paper-thread-general");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        WorkbenchWorkflowResult result = engine.execute(
                planned.runId(), "task-general-conversation", null);

        assertThat(result.answer()).contains("O(n log n)");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.answerBlocks()).singleElement()
                .extracting(WorkbenchAnswerBlock::basis)
                .isEqualTo(WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE);
        assertCompleted(planned.runId(), 4);
    }

    @Test
    void explicitPaperQuestionNeverFallsThroughToAnUngroundedModelAnswer() {
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "你认为该文章最重要的一条公式是什么？", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "paper-thread-evidence");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        assertThatThrownBy(() -> engine.execute(
                planned.runId(), "task-paper-evidence", null))
                .isInstanceOf(AsyncTaskExecutionException.class);

        WorkbenchRunTrace failed = traceService.requireTrace(planned.runId());
        assertThat(failed.errorCode()).isEqualTo("NO_EVIDENCE");
        verify(modelService, times(0)).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
    }

    @Test
    void groundedFollowUpWithoutRetrievedEvidenceFailsBeforeCallingTheModel() {
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(List.of());
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "如果还要再选一条公式呢？", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "paper-thread-follow-up");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        assertThatThrownBy(() -> engine.execute(
                planned.runId(), "task-grounded-follow-up", null))
                .isInstanceOf(AsyncTaskExecutionException.class);

        WorkbenchRunTrace failed = traceService.requireTrace(planned.runId());
        assertThat(failed.errorCode()).isEqualTo("NO_EVIDENCE");
        verify(modelService, times(0)).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
    }

    @Test
    void currentSelectionHighlightIsDeterministicAndDoesNotCallTheModel() {
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L),
                "将这段文字高亮", 6_000));

        WorkbenchWorkflowResult result = engine.execute(
                planned.runId(), "task-current-selection-highlight", null);

        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.READY);
            assertThat(action.evidenceId()).startsWith("selection:");
            assertThat(action.targetText()).isEqualTo("selected");
            assertThat(action.targetBoxes()).isEqualTo(anchor(7L).boxes());
        });
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.selected()).isTrue();
            assertThat(evidence.locator().precision())
                    .isEqualTo(com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.TEXT_RANGE);
        });
        assertThat(result.contextMode()).isEqualTo(WorkbenchContextMode.ACTION_EXPLICIT);
        assertThat(result.answer()).contains("正在 PDF 中执行高亮");
        assertThat(traceService.requireTrace(planned.runId()).metrics().totalTokens()).isZero();
        verify(modelService, times(0)).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        verify(localEvidenceService, times(0)).retrieve(any(), any(), anyString(), anyInt());
        verify(wholeEvidenceService, times(0)).retrievePaper(
                any(), anyString(), anyList(), anyInt(), anyInt());
        assertCompleted(planned.runId(), 4);
    }

    @Test
    void explicitHighlightCommandUsesItsOwnTargetAndReturnsExecutableFormulaActions() {
        LayoutEvidence commonSinr = formulaEvidence(
                "lay_formula_4", "equation-region:p4-b0041", 4, 143, "Equation (4)");
        LayoutEvidence privateSinr = formulaEvidence(
                "lay_formula_5", "equation-region:p4-b0062", 4, 153, "Equation (5)");
        List<LayoutEvidence> formulaEvidence = List.of(commonSinr, privateSinr);
        when(wholeEvidenceService.retrievePaper(any(), anyString(), anyList(), anyInt(), anyInt()))
                .thenReturn(formulaEvidence);
        WorkbenchAnswerBlock commandAnswer = new WorkbenchAnswerBlock(
                "公共流和私有流的信干噪比公式位于公式 (4) 与公式 (5)。",
                WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(
                        new WorkbenchAnswerBlock.Citation(commonSinr.evidenceId(), commonSinr.text()),
                        new WorkbenchAnswerBlock.Citation(privateSinr.evidenceId(), privateSinr.text())),
                List.of("r1"));
        WorkbenchModelOutput commandOutput = new WorkbenchModelOutput(
                commandAnswer.text(), List.of(), null, List.of(commandAnswer), List.of());
        doReturn(new WorkbenchModelService.ModelCall(commandOutput, true, 50, 20, 70))
                .when(modelService).generate(eq(WorkbenchPlan.Workflow.SELECTION_QA), anyString(),
                        anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "将信噪比公式所在位置高亮", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "command-thread");
        WorkbenchRunTrace planned = traceService.plan(invocation);

        WorkbenchWorkflowResult result = engine.execute(
                planned.runId(), "task-explicit-highlight", null);

        ArgumentCaptor<String> retrievalQuery = ArgumentCaptor.forClass(String.class);
        verify(wholeEvidenceService).retrievePaper(
                any(), retrievalQuery.capture(), eq(List.of()), eq(12), eq(10_000));
        assertThat(retrievalQuery.getValue()).startsWith("信噪比公式").doesNotContain("这段方法");
        assertThat(result.contextMode()).isEqualTo(WorkbenchContextMode.ACTION_EXPLICIT);
        assertThat(result.contextInherited()).isFalse();
        assertThat(result.answer()).isEqualTo("已找到 2 处与“信噪比公式”匹配的内容，正在 PDF 中执行高亮。");
        assertThat(result.actions()).hasSize(2).allSatisfy(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.READY);
            assertThat(action.targetText()).isBlank();
            assertThat(action.page()).isEqualTo(4);
        });
        assertThat(result.actions()).extracting(WorkbenchAction::evidenceId)
                .containsExactly("lay_formula_4", "lay_formula_5");
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
    void accurateButIncompleteAnswerIsRepairedAgainstTheSameRequirementPlan() {
        List<WorkbenchAnswerRequirement> requirements = List.of(
                new WorkbenchAnswerRequirement("r1", WorkbenchAnswerRequirement.Type.DIRECT,
                        "回答用户的直接问题", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_p7", "Evidence for paper 7"))),
                new WorkbenchAnswerRequirement("r2", WorkbenchAnswerRequirement.Type.CONTEXT,
                        "补充影响结论的关键条件", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_p7", "Evidence for paper 7"))));
        WorkbenchAnswerBlock direct = new WorkbenchAnswerBlock(
                "这是用户所需的直接答案。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_p7", "Evidence for paper 7")),
                List.of("r1"));
        WorkbenchAnswerBlock context = new WorkbenchAnswerBlock(
                "这里同时补充影响结论的关键条件。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_p7", "Evidence for paper 7")),
                List.of("r2"));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return new WorkbenchModelService.ModelCall(new WorkbenchModelOutput(
                        "这是用户所需的直接答案。", List.of(), null, List.of(direct), requirements),
                        true, 100, 50, 150);
            }
            WorkbenchModelOutput previous = invocation.getArgument(5);
            List<String> issues = invocation.getArgument(6);
            assertThat(previous.requirements()).containsExactlyElementsOf(requirements);
            assertThat(issues).anyMatch(issue -> issue.contains("required answer item r2 is missing"));
            return new WorkbenchModelService.ModelCall(new WorkbenchModelOutput(
                    "这是用户所需的直接答案。这里同时补充影响结论的关键条件。", List.of(), null,
                    List.of(direct, context), requirements), true, 120, 60, 180);
        }).when(modelService).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释论文方法", 6_000));

        WorkbenchWorkflowResult result = engine.execute(
                planned.runId(), "task-requirement-repair", null);

        assertThat(result.answer()).contains("直接答案", "关键条件");
        assertThat(result.repairCount()).isEqualTo(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void terminalEvidenceRejectionPersistsTheActualGateIssues() {
        doReturn(new WorkbenchModelService.ModelCall(
                        new WorkbenchModelOutput("plain answer without citations", List.of(), null),
                        false, 10, 5, 15))
                .when(modelService).generate(
                        any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchRunTrace planned = traceService.plan(new WorkbenchInvocation(
                List.of(7L), "论文在哪定义 SINR？", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "gate-audit-thread"));

        assertThatThrownBy(() -> engine.execute(planned.runId(), "task-gate-audit", null))
                .isInstanceOf(AsyncTaskExecutionException.class);

        WorkbenchRunTrace failed = traceService.requireTrace(planned.runId());
        assertThat(failed.status()).isEqualTo(WorkbenchRunStatus.FAILED);
        assertThat(failed.steps().get(2).inputSummary().toString())
                .contains("repairIssues", "selection answer has no claims");
        assertThat(failed.steps().get(3).outputSummary().toString())
                .contains("issues", "model output is not structured JSON", "selection answer has no claims");
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
    void providerFailureDuringEvidenceRepairIsTerminalWithoutCorruptTaskReplay() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return new WorkbenchModelService.ModelCall(
                        new WorkbenchModelOutput("not-json", List.of(), null), false, 10, 5, 15);
            }
            throw new WorkbenchModelException(
                    "MODEL_CALL_FAILED", "模型服务暂时不可用", true, 30, 20, 50, null, 1);
        }).when(modelService).generate(
                any(), anyString(), anyMap(), anyList(), anyInt(), any(), anyList());
        WorkbenchRunTrace planned = traceService.plan(invocation(
                WorkbenchIntent.ASK_SELECTION, List.of(7L), anchor(7L), "解释选区", 6_000));

        assertThatThrownBy(() -> engine.execute(planned.runId(), "task-repair-provider-failure", null))
                .isInstanceOf(AsyncTaskExecutionException.class)
                .satisfies(error -> {
                    AsyncTaskExecutionException taskError = (AsyncTaskExecutionException) error;
                    assertThat(taskError.isRetryable()).isFalse();
                    assertThat(taskError.getFailureCode()).isEqualTo("MODEL_CALL_FAILED");
                });

        WorkbenchRunTrace failed = traceService.requireTrace(planned.runId());
        assertThat(failed.status()).isEqualTo(WorkbenchRunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(failed.metrics().repairCount()).isEqualTo(1);
        assertThat(failed.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.FAILED);
        assertThat(calls).hasValue(2);
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
        WorkbenchConversationRelation relation = new WorkbenchConversationClassifier(
                new WorkbenchRetrievalPlanner(), new WorkbenchCommandPlanner()).classify(
                trace.invocation().question(), turns.isEmpty() ? List.of() : List.of(
                        new PaperConversationTurn(1, "prior-run", turns.get(0).question(),
                                turns.get(0).answer(), turns.get(0).evidenceBlockIds(),
                                List.of(), List.of(), Instant.now())));
        return new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, version.paperId(), version.documentHash(),
                version.parserVersion(), trace.invocation().conversationId(), trace.invocation().question(),
                trace.invocation().selectionAnchor() == null ? "" : "selected", "",
                trace.invocation().selectionAnchor() == null ? List.of() : List.of("p1-b0001"),
                PaperContextSnapshot.selectionFingerprint(trace.invocation().selectionAnchor()), "", turns, relation,
                List.of(),
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

    private LayoutEvidence formulaEvidence(String evidenceId,
                                            String blockId,
                                            int page,
                                            int readingOrder,
                                            String equationLabel) {
        NormalizedBoundingBox bbox = new NormalizedBoundingBox(
                0.14, 0.55 + Math.max(0, readingOrder - 143) * 0.01, 0.35, 0.04);
        return new LayoutEvidence(evidenceId, 7L, blockId, page, bbox,
                DocumentBlockRole.FORMULA, readingOrder,
                List.of("II. SYSTEM MODEL", equationLabel), "[公式区域]", 0.95,
                false, 0.92, hash(7L), "parser-v1", DocumentBlockContentMode.REGION,
                "", List.of(), List.of(),
                com.research.assistant.service.pdf.layout.EvidenceOrigin.CURRENT_LAYOUT,
                new EvidenceLocator(bbox, "", EvidenceLocator.Precision.FORMULA_REGION),
                List.of("FORMULA"));
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
