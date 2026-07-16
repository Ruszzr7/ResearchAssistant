package com.research.assistant.service.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchOutputQualityGateTest {

    private final WorkbenchOutputQualityGate gate = new WorkbenchOutputQualityGate();

    @Test
    void researchGapRequiresGroundedCoverageCandidateLanguageAndValidation() {
        WorkbenchModelOutput valid = new WorkbenchModelOutput(
                ("## 候选空白\n现有论文在统一约束和验证场景上呈现不同覆盖边界。"
                        + "该研究空白仍需扩大检索范围并通过对照实验验证。\n").repeat(5),
                List.of(
                        claim("Paper 1 边界", "e1"),
                        claim("Paper 2 边界", "e2"),
                        claim("Paper 3 边界", "e3")),
                null);

        assertThat(gate.validate(WorkbenchPlan.Workflow.RESEARCH_GAP, valid, true, 3)).isEmpty();

        WorkbenchModelOutput unverified = new WorkbenchModelOutput(
                "这是确定的领域结论，没有任何后续步骤。".repeat(12),
                List.of(claim("only one", "e1")), null);
        assertThat(gate.validate(WorkbenchPlan.Workflow.RESEARCH_GAP, unverified, true, 3))
                .contains("research gap analysis is missing candidate gaps",
                        "research gap analysis is missing validation steps",
                        "research gap analysis has too few grounded claims");
    }

    @Test
    void paperImprovementRequiresActionableValidatedEntryPoints() {
        WorkbenchModelOutput valid = new WorkbenchModelOutput(
                ("## 改进空间与研究切入点\n当前假设边界可通过扩展场景和对照实验验证。\n").repeat(8),
                List.of(claim("假设边界", "e1"), claim("评价指标", "e2"), claim("实验设计", "e3")),
                null);
        assertThat(gate.validate(WorkbenchPlan.Workflow.PAPER_IMPROVEMENT, valid, true, 1)).isEmpty();

        WorkbenchModelOutput weak = new WorkbenchModelOutput(
                "这篇论文还有一些普通问题。".repeat(20), List.of(claim("问题", "e1")), null);
        assertThat(gate.validate(WorkbenchPlan.Workflow.PAPER_IMPROVEMENT, weak, true, 1))
                .contains("paper improvement analysis is missing research entry points",
                        "paper improvement analysis is missing validation steps",
                        "paper improvement analysis has fewer than three grounded claims");
    }

    private WorkbenchEvidenceGate.GroundedClaim claim(String text, String evidenceId) {
        return new WorkbenchEvidenceGate.GroundedClaim(text, List.of(evidenceId));
    }
}
