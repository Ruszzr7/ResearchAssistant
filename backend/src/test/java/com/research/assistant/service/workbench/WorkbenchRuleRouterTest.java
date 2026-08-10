package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkbenchRuleRouterTest {

    private final WorkbenchRuleRouter router = new WorkbenchRuleRouter();

    @Test
    void autoRoutesPreciseSelectionToFixedAllowListedWorkflow() {
        WorkbenchPlan plan = router.route(invocation(
                List.of(7L), WorkbenchIntent.AUTO, null, anchor(SelectionAnchorKind.TEXT), 6));

        assertThat(plan.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(plan.scope()).isEqualTo(WorkbenchPlan.Scope.SELECTION);
        assertThat(plan.steps()).extracting(WorkbenchPlan.Step::skill).containsExactly(
                WorkbenchPlan.Skill.RESOLVE_SELECTION_CONTEXT,
                WorkbenchPlan.Skill.RETRIEVE_LOCAL_EVIDENCE,
                WorkbenchPlan.Skill.SYNTHESIZE_EVIDENCE_ANSWER,
                WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER);
        assertThat(plan.allowedSkills()).containsExactlyInAnyOrderElementsOf(
                plan.steps().stream().map(WorkbenchPlan.Step::skill).toList());
        assertThat(plan.repairLimit()).isEqualTo(1);
        assertThat(plan.tokenBudget()).isEqualTo(10_000);
    }

    @Test
    void acceptsSelectionConversationIdAndRejectsInvalidOrUnrelatedIds() {
        WorkbenchInvocation followUp = new WorkbenchInvocation(
                List.of(7L), "继续解释", WorkbenchIntent.ASK_SELECTION, null,
                anchor(SelectionAnchorKind.TEXT), 6, 0, "", "selection-thread_1");

        assertThat(router.route(followUp).workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);

        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(7L), "继续解释", WorkbenchIntent.ASK_SELECTION, null,
                anchor(SelectionAnchorKind.TEXT), 6, 0, "", "bad id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(7L), "全文分析", WorkbenchIntent.ANALYZE_PAPER, null,
                null, 6, 0, "", "selection-thread_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supported");
    }

    @Test
    void routesAConversationWithoutSelectionToPaperEvidence() {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "这篇论文的核心贡献是什么？", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 0, "", "selection-thread_2");

        WorkbenchPlan plan = router.route(invocation);

        assertThat(plan.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(plan.scope()).isEqualTo(WorkbenchPlan.Scope.PAPER);
        assertThat(plan.evidenceRequired()).isTrue();
        assertThat(plan.steps()).extracting(WorkbenchPlan.Step::skill).containsExactly(
                WorkbenchPlan.Skill.ENSURE_LAYOUT_ARTIFACT,
                WorkbenchPlan.Skill.RETRIEVE_PAPER_EVIDENCE,
                WorkbenchPlan.Skill.SYNTHESIZE_EVIDENCE_ANSWER,
                WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER);
    }

    @Test
    void requiresEvidenceForPaperLocationButNotForOrdinaryConversation() {
        WorkbenchPlan location = router.route(new WorkbenchInvocation(
                List.of(7L), "为我找出信噪比公式在哪", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 0, "", "selection-thread_3"));
        WorkbenchPlan ordinary = router.route(new WorkbenchInvocation(
                List.of(7L), "帮我写一句今日学习计划", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 0, "", "selection-thread_3"));

        assertThat(location.evidenceRequired()).isTrue();
        assertThat(ordinary.evidenceRequired()).isFalse();
    }

    @Test
    void regionAnchorDowngradesScopeWithoutChangingAllowedSkills() {
        WorkbenchPlan plan = router.route(invocation(
                List.of(7L), WorkbenchIntent.ASK_SELECTION, WorkbenchPlan.Scope.SELECTION,
                anchor(SelectionAnchorKind.REGION), 6));

        assertThat(plan.scope()).isEqualTo(WorkbenchPlan.Scope.REGION);
        assertThat(plan.workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
    }

    @Test
    void autoRoutesOnePaperToAnalysisAndMultiplePapersToComparison() {
        WorkbenchPlan analysis = router.route(new WorkbenchInvocation(
                List.of(1L), "", WorkbenchIntent.AUTO, null, null, 6, 0));
        WorkbenchPlan comparison = router.route(new WorkbenchInvocation(
                List.of(1L, 2L), "比较方法", WorkbenchIntent.AUTO, null, null, 6, 0));

        assertThat(analysis.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_ANALYSIS);
        assertThat(analysis.steps()).hasSize(5);
        assertThat(comparison.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_COMPARISON);
        assertThat(comparison.scope()).isEqualTo(WorkbenchPlan.Scope.COMPARISON);
    }

    @Test
    void rejectsScopeExpansionAndBudgetsBelowFixedPlan() {
        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(1L), "解释", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, anchor(SelectionAnchorKind.TEXT), 6, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");

        assertThatThrownBy(() -> router.route(invocation(
                List.of(7L), WorkbenchIntent.ASK_SELECTION, null, anchor(SelectionAnchorKind.TEXT), 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSteps");
    }

    @Test
    void comparisonCannotBeSilentlyExpandedFromOnePaper() {
        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(1L), "比较", WorkbenchIntent.COMPARE_PAPERS, null, null, 6, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void paperImprovementUsesOnePaperAndItsOwnFixedSkill() {
        WorkbenchPlan plan = router.route(new WorkbenchInvocation(
                List.of(1L), "分析可检验的改进空间", WorkbenchIntent.IDENTIFY_PAPER_IMPROVEMENTS,
                null, null, 6, 0));

        assertThat(plan.workflow()).isEqualTo(WorkbenchPlan.Workflow.PAPER_IMPROVEMENT);
        assertThat(plan.scope()).isEqualTo(WorkbenchPlan.Scope.PAPER);
        assertThat(plan.steps()).extracting(WorkbenchPlan.Step::skill).containsExactly(
                WorkbenchPlan.Skill.ENSURE_LAYOUT_ARTIFACT,
                WorkbenchPlan.Skill.RETRIEVE_PAPER_EVIDENCE,
                WorkbenchPlan.Skill.IDENTIFY_PAPER_IMPROVEMENTS,
                WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER);
        assertThat(plan.tokenBudget()).isEqualTo(14_000);

        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(1L, 2L), "分析改进空间", WorkbenchIntent.IDENTIFY_PAPER_IMPROVEMENTS,
                null, null, 6, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void researchGapUsesFixedGroundedWorkflowAndRequiresThreePapers() {
        WorkbenchPlan plan = router.route(new WorkbenchInvocation(
                List.of(1L, 2L, 3L), "识别仍需验证的候选研究空白",
                WorkbenchIntent.FIND_RESEARCH_GAPS, null, null, 6, 0));

        assertThat(plan.workflow()).isEqualTo(WorkbenchPlan.Workflow.RESEARCH_GAP);
        assertThat(plan.scope()).isEqualTo(WorkbenchPlan.Scope.COMPARISON);
        assertThat(plan.steps()).extracting(WorkbenchPlan.Step::skill).containsExactly(
                WorkbenchPlan.Skill.ENSURE_LAYOUT_ARTIFACT,
                WorkbenchPlan.Skill.RETRIEVE_COMPARISON_EVIDENCE,
                WorkbenchPlan.Skill.IDENTIFY_RESEARCH_GAPS,
                WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER);
        assertThat(plan.tokenBudget()).isEqualTo(20_000);

        assertThatThrownBy(() -> router.route(new WorkbenchInvocation(
                List.of(1L, 2L), "识别研究空白", WorkbenchIntent.FIND_RESEARCH_GAPS,
                null, null, 6, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least three");
    }

    private WorkbenchInvocation invocation(List<Long> paperIds,
                                           WorkbenchIntent intent,
                                           WorkbenchPlan.Scope scope,
                                           SelectionAnchor anchor,
                                           int maxSteps) {
        return new WorkbenchInvocation(paperIds, "解释所选内容", intent, scope, anchor, maxSteps, 0);
    }

    private SelectionAnchor anchor(SelectionAnchorKind kind) {
        return new SelectionAnchor(7L, 1,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected text", List.of("p1-b0001"), null, kind, 0.9,
                "a".repeat(64), "parser-v1");
    }
}
