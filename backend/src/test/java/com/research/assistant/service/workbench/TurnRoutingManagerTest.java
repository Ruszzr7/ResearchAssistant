package com.research.assistant.service.workbench;

import com.research.assistant.service.memory.PaperConversationTurn;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnRoutingManagerTest {

    private final WorkbenchCommandPlanner commandPlanner = new WorkbenchCommandPlanner();
    private final TurnRoutingManager router = new TurnRoutingManager(
            commandPlanner, new WorkbenchRetrievalPlanner());

    @Test
    void routesSelectionQuestionsAndActionsBeforeConversationClassification() {
        assertRoute("解释这段公式", selection(), List.of(), WorkbenchTurnRoute.Type.SELECTION_QA);
        assertRoute("将这段文字高亮", selection(), List.of(), WorkbenchTurnRoute.Type.SELECTION_ACTION);
        assertRoute("把它下划线", selection(), List.of(turn()), WorkbenchTurnRoute.Type.SELECTION_ACTION);
        assertRoute("给这段添加批注：这里是关键假设", selection(), List.of(),
                WorkbenchTurnRoute.Type.SELECTION_ACTION);
    }

    @Test
    void separatesPaperCommandsFollowUpsPaperQuestionsAndGeneralChat() {
        assertRoute("找出私有流速率闭式表达式并高亮", null, List.of(),
                WorkbenchTurnRoute.Type.PAPER_ACTION);
        assertRoute("把刚才那条公式下划线", null, List.of(turn()),
                WorkbenchTurnRoute.Type.FOLLOW_UP_ACTION);
        assertRoute("如果还要再选一条公式呢？", null, List.of(turn()),
                WorkbenchTurnRoute.Type.FOLLOW_UP_QA);
        assertRoute("这篇论文最重要的公式是什么？", null, List.of(),
                WorkbenchTurnRoute.Type.PAPER_QA);
        assertRoute("快速排序的平均复杂度是什么？", null, List.of(turn()),
                WorkbenchTurnRoute.Type.GENERAL_CHAT);
    }

    @Test
    void exposesActionTypeTargetSourceAndOptionalContent() {
        WorkbenchTurnRoute route = router.route(invocation(
                "给这段添加批注：需要核对边界条件", selection()), List.of());

        assertThat(route.command().type()).isEqualTo(WorkbenchCommandSpec.Type.ADD_COMMENT);
        assertThat(route.command().referenceMode()).isEqualTo(
                WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION);
        assertThat(route.command().content()).isEqualTo("需要核对边界条件");
        assertThat(route.callModel()).isFalse();
        assertThat(route.retrievePaperEvidence()).isFalse();
    }

    @Test
    void routesImplicitDomainQuestionThroughTheCurrentPaperProfile() {
        WorkbenchTurnRoute route = router.route(
                invocation("遍历速率闭式下界是如何推导的？", null), List.of(),
                "方法：推导公共流和私有流的遍历速率闭式下界；主要发现：给出可优化表达式");

        assertThat(route.type()).isEqualTo(WorkbenchTurnRoute.Type.PAPER_QA);
        assertThat(route.retrievePaperEvidence()).isTrue();
    }

    @Test
    void usesTheBoundedSemanticFallbackForAConversationalTypo() {
        WorkbenchRouteClassifier classifier = mock(WorkbenchRouteClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyList())).thenReturn(
                java.util.Optional.of(new WorkbenchRouteClassifier.Decision(
                        WorkbenchTurnRoute.Type.FOLLOW_UP_QA, .94, "再选择一条公式")));
        TurnRoutingManager semanticRouter = new TurnRoutingManager(
                commandPlanner, new WorkbenchRetrievalPlanner(), classifier);

        WorkbenchTurnRoute route = semanticRouter.route(
                invocation("如果在选择一条公式呢？", null), List.of(turn()));

        assertThat(route.type()).isEqualTo(WorkbenchTurnRoute.Type.FOLLOW_UP_QA);
        assertThat(route.reasons()).contains("semantic-fallback:FOLLOW_UP_QA");
    }

    @Test
    void treatsCorePaperConclusionAsPaperQaWithoutDependingOnHistory() {
        assertRoute("文章最核心的结论是什么？", null, List.of(turn()),
                WorkbenchTurnRoute.Type.PAPER_QA);
    }

    @Test
    void routesEveryAllowListedPdfOperationWithoutCallingTheAnswerModel() {
        assertAction("将这段文字高亮", WorkbenchCommandSpec.Type.HIGHLIGHT);
        assertAction("将这段文字添加下划线", WorkbenchCommandSpec.Type.UNDERLINE);
        assertAction("给这段添加笔记：核对假设", WorkbenchCommandSpec.Type.ADD_NOTE);
        assertAction("给这段添加批注：关键边界", WorkbenchCommandSpec.Type.ADD_COMMENT);
        assertAction("跳转到这段文字", WorkbenchCommandSpec.Type.NAVIGATE);
    }

    private void assertRoute(String question,
                             SelectionAnchor anchor,
                             List<PaperConversationTurn> turns,
                             WorkbenchTurnRoute.Type expected) {
        assertThat(router.route(invocation(question, anchor), turns).type()).isEqualTo(expected);
    }

    private void assertAction(String question, WorkbenchCommandSpec.Type expected) {
        WorkbenchTurnRoute route = router.route(invocation(question, selection()), List.of());
        assertThat(route.type()).isEqualTo(WorkbenchTurnRoute.Type.SELECTION_ACTION);
        assertThat(route.command().type()).isEqualTo(expected);
        assertThat(route.callModel()).isFalse();
    }

    private WorkbenchInvocation invocation(String question, SelectionAnchor anchor) {
        return new WorkbenchInvocation(List.of(7L), question, WorkbenchIntent.ASK_SELECTION,
                anchor == null ? WorkbenchPlan.Scope.PAPER : WorkbenchPlan.Scope.SELECTION,
                anchor, 6, 10_000, "", "conversation-7");
    }

    private SelectionAnchor selection() {
        return new SelectionAnchor(7L, 4,
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)),
                "selected paper text", List.of("p4-b10"), null,
                SelectionAnchorKind.TEXT, .99, "a".repeat(64), "parser-v1");
    }

    private PaperConversationTurn turn() {
        return new PaperConversationTurn(1, "run-1",
                "你认为这篇论文最重要的公式是什么？", "我选择公式 (21)。",
                List.of(), List.of(), List.of(), Instant.now());
    }
}
