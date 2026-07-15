package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchEvidenceGateTest {

    private final WorkbenchEvidenceGate gate = new WorkbenchEvidenceGate();

    @Test
    void passesOnlyClaimsGroundedInTheCurrentEvidenceSet() {
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "结论 A；结论 B。",
                List.of(
                        new WorkbenchEvidenceGate.GroundedClaim("结论 A", List.of("lay_a")),
                        new WorkbenchEvidenceGate.GroundedClaim("结论 B", List.of("lay_b"))));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                draft, List.of(evidence("lay_a"), evidence("lay_b")),
                WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
        assertThat(result.claimCoverage()).isEqualTo(1.0);
        assertThat(result.validEvidenceIds()).containsExactly("lay_a", "lay_b");
        assertThat(result.invalidEvidenceIds()).isEmpty();
    }

    @Test
    void unknownCitationRequestsOneRepairThenRejects() {
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "unsupported", List.of(new WorkbenchEvidenceGate.GroundedClaim(
                "unsupported", List.of("invented"))));

        WorkbenchEvidenceGate.GateResult first = gate.validate(
                draft, List.of(evidence("lay_a")), WorkbenchEvidenceGate.GatePolicy.strict(0));
        WorkbenchEvidenceGate.GateResult second = gate.validate(
                draft, List.of(evidence("lay_a")), WorkbenchEvidenceGate.GatePolicy.strict(1));

        assertThat(first.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REPAIR);
        assertThat(second.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REJECT);
        assertThat(second.invalidEvidenceIds()).containsExactly("invented");
    }

    @Test
    void partialClaimCoverageCannotPassStrictGate() {
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "two claims", List.of(
                new WorkbenchEvidenceGate.GroundedClaim("grounded", List.of("lay_a")),
                new WorkbenchEvidenceGate.GroundedClaim("missing", List.of())));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                draft, List.of(evidence("lay_a")), WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REPAIR);
        assertThat(result.claimCoverage()).isEqualTo(0.5);
        assertThat(result.issues()).anyMatch(issue -> issue.contains("coverage"));
    }

    @Test
    void emptyEvidenceSetNeverProducesGroundedAnswer() {
        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft("answer", List.of()),
                List.of(), WorkbenchEvidenceGate.GatePolicy.strict(1));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REJECT);
        assertThat(result.issues()).contains("evidence set is empty", "grounded claims are required");
    }

    private LayoutEvidence evidence(String id) {
        return new LayoutEvidence(id, 1L, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04),
                DocumentBlockRole.BODY, 1, List.of("Introduction"), "evidence text",
                0.9, true, 0.9, "a".repeat(64), "parser-v1");
    }
}
