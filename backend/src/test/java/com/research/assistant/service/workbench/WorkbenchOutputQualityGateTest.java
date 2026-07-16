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

    private WorkbenchEvidenceGate.GroundedClaim claim(String text, String evidenceId) {
        return new WorkbenchEvidenceGate.GroundedClaim(text, List.of(evidenceId));
    }
}
