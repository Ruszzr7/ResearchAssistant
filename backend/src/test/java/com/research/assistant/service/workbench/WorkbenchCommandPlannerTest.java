package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchCommandPlannerTest {

    private final WorkbenchCommandPlanner planner = new WorkbenchCommandPlanner();

    @Test
    void recognizesOnlyAnExplicitFindAndHighlightCommand() {
        assertThat(planner.parse("高亮")).get().extracting(WorkbenchCommandSpec::referenceMode)
                .isEqualTo(WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION);
        assertThat(planner.parse("高亮选定内容")).get().extracting(WorkbenchCommandSpec::referenceMode)
                .isEqualTo(WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION);
        assertThat(planner.parse("高亮这段公式")).get().extracting(WorkbenchCommandSpec::referenceMode)
                .isEqualTo(WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION);
        assertThat(planner.highlightTarget("找出 SINR 公式并高亮")).isEqualTo("SINR 公式");
        assertThat(planner.highlightTarget("请定位“perfect SIC”然后突出显示"))
                .isEqualTo("perfect SIC");
        assertThat(planner.highlightTarget("将信噪比公式对应的区域高亮"))
                .isEqualTo("信噪比公式");
        assertThat(planner.highlightTarget("将信噪比公式所在位置高亮"))
                .isEqualTo("信噪比公式");
        assertThat(planner.highlightTarget("把 Equation (4) 区域标黄"))
                .isEqualTo("Equation (4)");
        assertThat(planner.highlightTarget("请解释高亮为什么重要")).isBlank();
        assertThat(planner.highlightTarget("SINR 在哪里？")).isBlank();
    }

    @Test
    void explicitCommandDropsStaleConversationHintsAtTheCommandBoundary() {
        PaperContextSnapshot context = new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, 7L, "a".repeat(64), "parser-v1",
                "session-7", "将信噪比公式所在位置高亮", "selected equation", "",
                List.of("p4-b0041"), "selection", "paper profile",
                List.of(new PaperContextSnapshot.ConversationItem(
                        1L, "旧问题", "旧回答", List.of("p1-b0001"))),
                List.of(), List.of("CURRENT_QUESTION"),
                new PaperContextSnapshot.Budget(8_000, 20, 20, 0, 10), false, Instant.now());

        assertThat(planner.retrievalQuery(context))
                .isEqualTo("信噪比公式")
                .doesNotContain("旧问题", "历史追问");
        assertThat(planner.preferredEvidenceBlockIds(context)).isEmpty();
        assertThat(planner.modelQuestion(context))
                .contains("操作目标：信噪比公式")
                .doesNotContain("当前选区：selected equation")
                .doesNotContain("同一论文与同一对话", "旧回答");
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

    @Test
    void bindsThisTextDirectlyToTheCanonicalCurrentSelection() {
        SelectionAnchor anchor = new SelectionAnchor(
                7L, 4, List.of(new NormalizedBoundingBox(0.08, 0.2, 0.42, 0.05)),
                "Fig. 1: RSMA-assisted FBL transmissions in a high-mobility scenario.",
                List.of("p4-b0022"), null, SelectionAnchorKind.TEXT, 0.98,
                "a".repeat(64), "parser-v1");
        LayoutEvidence selected = new LayoutEvidence(
                "lay-selected", 7L, "p4-b0022", 4,
                new NormalizedBoundingBox(0.08, 0.2, 0.42, 0.05),
                DocumentBlockRole.CAPTION, 22, List.of("Fig. 1"),
                anchor.anchorText(), 1, true, 0.98, "a".repeat(64), "parser-v1");

        List<WorkbenchAction> actions = planner.planCurrentSelection(
                trace("将这段文字高亮", anchor), List.of(selected));

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.READY);
            assertThat(action.evidenceId()).isEqualTo("lay-selected");
            assertThat(action.targetText()).isEqualTo(anchor.anchorText());
        });
    }

    @Test
    void createsOneActionForEveryCitedFormulaRegionInsteadOfHighlightingDefinitionText() {
        LayoutEvidence definition = evidence("lay-definition",
                "signal-to-interference plus noise ratio (SINR) for the common stream");
        LayoutEvidence formula4 = formula("lay-formula-4", "equation-region:p4-b0041", 4);
        LayoutEvidence formula5 = formula("lay-formula-5", "equation-region:p4-b0062", 5);
        WorkbenchAnswerBlock overview = new WorkbenchAnswerBlock(
                "本文给出了公共流和私有流 SINR。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation(
                        definition.evidenceId(), "signal-to-interference plus noise ratio (SINR)")),
                List.of("r1"));
        WorkbenchAnswerBlock targets = new WorkbenchAnswerBlock(
                "对应公式为公式 (4) 和公式 (5)。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(
                        new WorkbenchAnswerBlock.Citation(formula4.evidenceId(), formula4.text()),
                        new WorkbenchAnswerBlock.Citation(formula5.evidenceId(), formula5.text())),
                List.of("r1"));
        WorkbenchModelOutput output = new WorkbenchModelOutput(
                "", List.of(), null, List.of(overview, targets), List.of());

        List<WorkbenchAction> actions = planner.plan(
                trace("将信噪比公式所在位置高亮"), output,
                List.of(definition, formula4, formula5));

        assertThat(actions).hasSize(2)
                .extracting(WorkbenchAction::evidenceId)
                .containsExactly("lay-formula-4", "lay-formula-5");
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.status()).isEqualTo(WorkbenchAction.Status.READY);
            assertThat(action.targetText()).isEmpty();
        });
    }

    @Test
    void equationNumberCommandKeepsOnlyTheRequestedFormula() {
        LayoutEvidence formula4 = formula("lay-formula-4", "equation-region:p4-b0041", 4);
        LayoutEvidence formula5 = formula("lay-formula-5", "equation-region:p4-b0062", 5);
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "公式 (4) 和公式 (5)。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(
                        new WorkbenchAnswerBlock.Citation(formula4.evidenceId(), formula4.text()),
                        new WorkbenchAnswerBlock.Citation(formula5.evidenceId(), formula5.text())),
                List.of("r1"));

        List<WorkbenchAction> actions = planner.plan(
                trace("把 Equation (5) 区域标黄"),
                new WorkbenchModelOutput("", List.of(), null, List.of(block), List.of()),
                List.of(formula4, formula5));

        assertThat(actions).singleElement()
                .extracting(WorkbenchAction::evidenceId).isEqualTo("lay-formula-5");
    }

    private WorkbenchRunTrace trace(String question) {
        return trace(question, null);
    }

    private WorkbenchRunTrace trace(String question, SelectionAnchor anchor) {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), question, WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, anchor, 6, 10_000, "", "session-7");
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

    private LayoutEvidence formula(String id, String blockId, int number) {
        return new LayoutEvidence(id, 7L, blockId, 4,
                new NormalizedBoundingBox(0.1, 0.2 + number * 0.05, 0.4, 0.04),
                DocumentBlockRole.FORMULA, 10 + number,
                List.of("II. SYSTEM MODEL", "Equation (" + number + ")"),
                "[公式区域：未获得可信 LaTeX，仅可按页面区域定位和核对]",
                0.9, false, 0.9, "a".repeat(64), "parser-v1",
                DocumentBlockContentMode.REGION, "");
    }
}
