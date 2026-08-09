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
    void allowsAnOrdinaryConversationTurnWhenPaperRetrievalFindsNothing() {
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "快速排序的平均时间复杂度是 O(n log n)。",
                WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE, List.of(), List.of("r1"));
        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(block.text(), List.of(), true,
                        List.of(block), List.of(new WorkbenchAnswerRequirement(
                        "r1", WorkbenchAnswerRequirement.Type.DIRECT,
                        "快速排序的复杂度是什么？", true, List.of()))),
                List.of(), WorkbenchEvidenceGate.GatePolicy.conversation(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
        assertThat(result.claimCoverage()).isEqualTo(1);
    }

    @Test
    void permitsExplicitGeneralKnowledgeWithoutInventingPaperCitations() {
        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(
                        "这是通用概念解释，不是本文结论。", List.of(), true),
                List.of(evidence("lay_selected", 7L, true)),
                WorkbenchEvidenceGate.GatePolicy.selection(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
        assertThat(result.claimCoverage()).isEqualTo(1);
        assertThat(result.validEvidenceIds()).isEmpty();
    }

    @Test
    void rejectsAQuoteThatCannotBeLocatedInTheCitedEvidence() {
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "本文在系统模型中定义 SINR。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "invented source quote")));
        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(block.text(),
                        List.of(new WorkbenchEvidenceGate.GroundedClaim(
                                block.text(), List.of("lay_sinr"))), false, List.of(block)),
                List.of(evidence("lay_sinr", 7L, false)),
                WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REPAIR);
        assertThat(result.issues()).contains("answer block 0 citation quote is not in evidence");
    }

    @Test
    void repairsACombinedClaimWhenItsQuoteOmitsAReferencedTechnicalFact() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_sinr", 7L, "p4-b0048", 4,
                new NormalizedBoundingBox(0.08, 0.58, 0.41, 0.12),
                DocumentBlockRole.BODY, 150, List.of("II. SYSTEM MODEL"),
                "The SINR of the weakest user should be focused. We assume perfect SIC.",
                0.95, false, 0.9, "a".repeat(64), "parser-v1");
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "论文关注最弱用户的 SINR，并假设完美 SIC。",
                WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_sinr", "The SINR of the weakest user should be focused.")));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(block.text(),
                        List.of(new WorkbenchEvidenceGate.GroundedClaim(
                                block.text(), List.of("lay_sinr"))), false, List.of(block)),
                List.of(source), WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REPAIR);
        assertThat(result.issues()).contains(
                "answer block 0 citation quotes omit source technical anchors: SIC");
    }

    @Test
    void acceptsTechnicalAnchorsWhenTheyAreCoveredAcrossSeveralCitations() {
        LayoutEvidence body = new LayoutEvidence(
                "lay_body", 7L, "p4-b0037", 4,
                new NormalizedBoundingBox(0.08, 0.52, 0.41, 0.04),
                DocumentBlockRole.BODY, 141, List.of("II. SYSTEM MODEL"),
                "The SINR for the common stream can be written as", 0.95, false,
                0.9, "a".repeat(64), "parser-v1");
        LayoutEvidence formula = new LayoutEvidence(
                "lay_formula", 7L, "equation-region:p4-b0041", 4,
                new NormalizedBoundingBox(0.14, 0.55, 0.35, 0.04),
                DocumentBlockRole.FORMULA, 143, List.of("II. SYSTEM MODEL", "Equation (4)"),
                "Equation (4)", 0.95, false, 0.9, "a".repeat(64), "parser-v1");
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "公共流 SINR 见 Equation (4)。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(
                        new WorkbenchAnswerBlock.Citation("lay_body", "SINR"),
                        new WorkbenchAnswerBlock.Citation("lay_formula", "Equation (4)")));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(block.text(),
                        List.of(new WorkbenchEvidenceGate.GroundedClaim(
                                block.text(), List.of("lay_body", "lay_formula"))),
                        false, List.of(block)),
                List.of(body, formula), WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
    }

    @Test
    void sectionHeadingWordsAreNotMistakenForUnquotedTechnicalFacts() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_sinr", 7L, "p4-b0037", 4,
                new NormalizedBoundingBox(0.08, 0.52, 0.41, 0.04),
                DocumentBlockRole.BODY, 141, List.of("II. SYSTEM MODEL"),
                "The SINR for the common stream can be written as", 0.95, false,
                0.9, "a".repeat(64), "parser-v1");
        LayoutEvidence heading = new LayoutEvidence(
                "lay_heading", 7L, "p4-b0036", 4,
                new NormalizedBoundingBox(0.08, 0.48, 0.41, 0.03),
                DocumentBlockRole.HEADING, 140, List.of("II. SYSTEM MODEL"),
                "II. SYSTEM MODEL", 0.95, false,
                0.9, "a".repeat(64), "parser-v1");
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "公共流 SINR 位于 II. SYSTEM MODEL。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "SINR"),
                        new WorkbenchAnswerBlock.Citation("lay_heading", "II.")));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                new WorkbenchEvidenceGate.AnswerDraft(block.text(),
                        List.of(new WorkbenchEvidenceGate.GroundedClaim(
                                block.text(), List.of("lay_sinr", "lay_heading"))), false, List.of(block)),
                List.of(source, heading), WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
    }

    @Test
    void serverCanonicalizesProviderParaphraseToAnExactEvidenceQuote() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_sinr", 7L, "p4-b0037", 4,
                new NormalizedBoundingBox(0.08, 0.52, 0.41, 0.04),
                DocumentBlockRole.BODY, 141, List.of("II. SYSTEM MODEL"),
                "The signal-to-interference plus noise ratio (SINR) for the common stream at vehicle-k can be written as",
                0.95, false, 0.9, "a".repeat(64), "parser-v1");
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "公共流 SINR 定义位于第 4 页系统模型。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "公共流 SINR 的定义")));
        WorkbenchModelOutput normalized = new WorkbenchModelOutput(
                block.text(), List.of(), null, List.of(block)).normalizeEvidenceQuotes(List.of(source));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                normalized.toGateDraft(WorkbenchPlan.Workflow.SELECTION_QA), List.of(source),
                WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
        assertThat(normalized.answerBlocks().get(0).citations().get(0).quote())
                .contains("signal-to-interference plus noise ratio", "SINR");
    }

    @Test
    void rejectsAnAccurateButIncompleteAnswerWhenARequiredItemIsMissing() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_method", 7L, "p2-b0010", 2,
                new NormalizedBoundingBox(0.08, 0.3, 0.4, 0.08),
                DocumentBlockRole.BODY, 40, List.of("Method"),
                "The method reduces complexity. It assumes perfect channel knowledge.",
                0.95, false, 0.9, "a".repeat(64), "parser-v1");
        List<WorkbenchAnswerRequirement> requirements = List.of(
                new WorkbenchAnswerRequirement("r1", WorkbenchAnswerRequirement.Type.DIRECT,
                        "说明方法的作用", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_method", "The method reduces complexity"))),
                new WorkbenchAnswerRequirement("r2", WorkbenchAnswerRequirement.Type.CONTEXT,
                        "说明关键假设", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_method", "It assumes perfect channel knowledge"))));
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "该方法降低了复杂度。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_method", "The method reduces complexity")), List.of("r1"));
        WorkbenchModelOutput output = new WorkbenchModelOutput(
                block.text(), List.of(), null, List.of(block), requirements);

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                output.toGateDraft(WorkbenchPlan.Workflow.SELECTION_QA), List.of(source),
                WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REPAIR);
        assertThat(result.issues()).contains(
                "required answer item r2 is missing: 说明关键假设");
    }

    @Test
    void acceptsDifferentQuestionTypesWhenEveryRequirementIsExplicitlyCovered() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_result", 7L, "p6-b0020", 6,
                new NormalizedBoundingBox(0.52, 0.4, 0.4, 0.08),
                DocumentBlockRole.BODY, 210, List.of("Experiments"),
                "Accuracy improves by 8 percent under low mobility. The gain decreases at high mobility.",
                0.95, false, 0.9, "a".repeat(64), "parser-v1");
        List<WorkbenchAnswerRequirement> requirements = List.of(
                new WorkbenchAnswerRequirement("r1", WorkbenchAnswerRequirement.Type.DIRECT,
                        "解释实验增益", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_result", "Accuracy improves by 8 percent under low mobility"))),
                new WorkbenchAnswerRequirement("r2", WorkbenchAnswerRequirement.Type.CONTEXT,
                        "说明适用范围", true, List.of(new WorkbenchAnswerBlock.Citation(
                        "lay_result", "The gain decreases at high mobility"))));
        List<WorkbenchAnswerBlock> blocks = List.of(
                new WorkbenchAnswerBlock("低移动性下准确率提升 8%。",
                        WorkbenchAnswerBlock.Basis.PAPER_FACT,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_result",
                                "Accuracy improves by 8 percent under low mobility")), List.of("r1")),
                new WorkbenchAnswerBlock("高移动性下增益会下降。",
                        WorkbenchAnswerBlock.Basis.PAPER_FACT,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_result",
                                "The gain decreases at high mobility")), List.of("r2")));
        WorkbenchModelOutput output = new WorkbenchModelOutput(
                "低移动性下准确率提升 8%；高移动性下增益下降。",
                List.of(), null, blocks, requirements);

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                output.toGateDraft(WorkbenchPlan.Workflow.SELECTION_QA), List.of(source),
                WorkbenchEvidenceGate.GatePolicy.strict(0));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.PASS);
    }

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

    @Test
    void selectionMustCiteTheSelectedPassage() {
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "answer", List.of(new WorkbenchEvidenceGate.GroundedClaim("claim", List.of("lay_context"))));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                draft,
                List.of(evidence("lay_selected", 7L, true), evidence("lay_context", 7L, false)),
                WorkbenchEvidenceGate.GatePolicy.selection(1));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REJECT);
        assertThat(result.issues()).contains("answer does not cite the selected passage");
    }

    @Test
    void comparisonMustCiteEveryRequestedPaper() {
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "answer", List.of(new WorkbenchEvidenceGate.GroundedClaim("only paper seven", List.of("lay_a"))));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                draft,
                List.of(evidence("lay_a", 7L, false), evidence("lay_b", 8L, false)),
                WorkbenchEvidenceGate.GatePolicy.comparison(1, java.util.Set.of(7L, 8L)));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REJECT);
        assertThat(result.issues()).contains("answer does not cite every required paper");
    }

    @Test
    void zeroRelevanceEvidenceCannotGroundAClaimMerelyBecauseItsIdExists() {
        LayoutEvidence unrelated = new LayoutEvidence(
                "lay_unrelated", 1L, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04),
                DocumentBlockRole.BODY, 1, List.of("Introduction"), "unrelated text",
                0, false, 0.9, "a".repeat(64), "parser-v1");
        WorkbenchEvidenceGate.AnswerDraft draft = new WorkbenchEvidenceGate.AnswerDraft(
                "unsupported", List.of(new WorkbenchEvidenceGate.GroundedClaim(
                "unsupported", List.of("lay_unrelated"))));

        WorkbenchEvidenceGate.GateResult result = gate.validate(
                draft, List.of(unrelated), WorkbenchEvidenceGate.GatePolicy.strict(1));

        assertThat(result.decision()).isEqualTo(WorkbenchEvidenceGate.Decision.REJECT);
        assertThat(result.validEvidenceIds()).isEmpty();
        assertThat(result.invalidEvidenceIds()).containsExactly("lay_unrelated");
        assertThat(result.issues()).contains("answer cites evidence with no query relevance");
    }

    private LayoutEvidence evidence(String id) {
        return evidence(id, 1L, true);
    }

    private LayoutEvidence evidence(String id, Long paperId, boolean selected) {
        return new LayoutEvidence(id, paperId, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04),
                DocumentBlockRole.BODY, 1, List.of("Introduction"), "evidence text",
                0.9, selected, 0.9, "a".repeat(64), "parser-v1");
    }
}
