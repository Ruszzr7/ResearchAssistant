package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchCommandPlannerTest {

    private final WorkbenchCommandPlanner planner = new WorkbenchCommandPlanner();

    @Test
    void recognizesOnlyAnExplicitFindAndHighlightCommand() {
        assertThat(planner.highlightTarget("找出 SINR 公式并高亮")).isEqualTo("SINR 公式");
        assertThat(planner.highlightTarget("请定位“perfect SIC”然后突出显示"))
                .isEqualTo("perfect SIC");
        assertThat(planner.highlightTarget("请解释高亮为什么重要")).isBlank();
        assertThat(planner.highlightTarget("SINR 在哪里？")).isBlank();
    }

    @Test
    void bindsTheActionToEvidenceAlreadyCitedByTheValidatedAnswer() {
        LayoutEvidence source = evidence("lay-sinr", "The signal-to-interference plus noise ratio (SINR) is defined here.");
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "SINR 定义位于系统模型部分。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay-sinr",
                        "signal-to-interference plus noise ratio (SINR)")), List.of("r1"));
        WorkbenchModelOutput output = new WorkbenchModelOutput(
                block.text(), List.of(), null, List.of(block), List.of());
        WorkbenchRunTrace trace = trace("为我找出 SINR 并高亮");

        List<WorkbenchAction> actions = planner.plan(trace, output, List.of(source));

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.READY);
            assertThat(action.type()).isEqualTo(WorkbenchAction.Type.HIGHLIGHT);
            assertThat(action.evidenceId()).isEqualTo("lay-sinr");
            assertThat(action.page()).isEqualTo(4);
            assertThat(action.targetText()).contains("noise ratio (SINR)");
        });
    }

    @Test
    void refusesToHighlightUncitedOrUnresolvedContent() {
        WorkbenchRunTrace trace = trace("找出不存在的术语并高亮");
        List<WorkbenchAction> actions = planner.plan(trace,
                new WorkbenchModelOutput("没有找到。", List.of(), null, List.of(), List.of()),
                List.of(evidence("lay-other", "unrelated paper text")));

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.UNRESOLVED);
            assertThat(action.evidenceId()).isBlank();
        });
    }

    private WorkbenchRunTrace trace(String question) {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), question, WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000, "", "session-7");
        List<WorkbenchPlan.Step> steps = List.of(
                WorkbenchPlan.Step.of(0, "准备版面制品", WorkbenchPlan.Skill.ENSURE_LAYOUT_ARTIFACT),
                WorkbenchPlan.Step.of(1, "检索全文证据", WorkbenchPlan.Skill.RETRIEVE_PAPER_EVIDENCE),
                WorkbenchPlan.Step.of(2, "生成证据回答", WorkbenchPlan.Skill.SYNTHESIZE_EVIDENCE_ANSWER),
                WorkbenchPlan.Step.of(3, "证据门禁", WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER));
        WorkbenchPlan plan = new WorkbenchPlan(
                WorkbenchPlan.Workflow.SELECTION_QA, WorkbenchPlan.Scope.PAPER, steps,
                Set.of(WorkbenchPlan.Skill.ENSURE_LAYOUT_ARTIFACT,
                        WorkbenchPlan.Skill.RETRIEVE_PAPER_EVIDENCE,
                        WorkbenchPlan.Skill.SYNTHESIZE_EVIDENCE_ANSWER,
                        WorkbenchPlan.Skill.VALIDATE_EVIDENCE_ANSWER),
                6, 10_000, false, 1);
        return new WorkbenchRunTrace("run-1", null, WorkbenchRunStatus.RUNNING, invocation, plan,
                List.of(), new WorkbenchRunTrace.Metrics(0, 0, 0, 0, 0, 0),
                null, null, null, null, null, null, null, List.of());
    }

    private LayoutEvidence evidence(String id, String text) {
        return new LayoutEvidence(id, 7L, "p4-b0010", 4,
                new NormalizedBoundingBox(0.1, 0.2, 0.4, 0.04),
                DocumentBlockRole.BODY, 10, List.of("II. SYSTEM MODEL"), text,
                0.9, false, 0.9, "a".repeat(64), "parser-v1");
    }
}
