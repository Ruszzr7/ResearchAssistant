package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperSourceIndexServiceTest {

    private final PaperSourceIndexService service = new PaperSourceIndexService();

    @Test
    void separatesTheoremResultFromProofStepAndPlainMention() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("t2", 6, 10, DocumentBlockRole.HEADING,
                        "Theorem 2. The lower bound Rk for the ergodic rate"),
                block("eq31", 6, 11, DocumentBlockRole.FORMULA,
                        "Rk = C(Gamma) - Q(beta). (31)"),
                block("proof", 6, 12, DocumentBlockRole.BODY, "Proof. We first approximate the mean."),
                block("eq34", 7, 13, DocumentBlockRole.FORMULA,
                        "Rk ≈ E C(Gamma) - E Q(beta). (34)"),
                block("mention34", 7, 14, DocumentBlockRole.FORMULA,
                        "Based on Lemma 6, we can make E[Y] ≈ E[Gamma] in (34).")));

        PaperSourceIndex index = service.build(artifact);

        assertThat(index.equations()).extracting(EquationEntity::number)
                .containsExactly("31", "34");
        EquationEntity result = index.equations().get(0);
        EquationEntity proofStep = index.equations().get(1);
        assertThat(result.relation()).isEqualTo(EquationEntity.Relation.THEOREM_RESULT);
        assertThat(result.theoremNumber()).isEqualTo("2");
        assertThat(proofStep.relation()).isEqualTo(EquationEntity.Relation.PROOF_STEP);
        assertThat(proofStep.mentions()).extracting(SourceAnchor::blockId).contains("mention34");
        assertThat(proofStep.definition().blockId()).isEqualTo("eq34");
    }

    @Test
    void formulaAnchorUnionsNearbyTallFormulaFragmentsButNotOtherColumn() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("eq", 4, 10, DocumentBlockRole.FORMULA, "Gamma = x / y (4)"),
                new DocumentBlock("tall", 4, new NormalizedBoundingBox(0.18, 0.19, 0.25, 0.08),
                        DocumentBlockRole.FORMULA, 11, List.of("System Model"), "", null, null,
                        0.8, DocumentBlockContentMode.REGION),
                new DocumentBlock("other-column", 4, new NormalizedBoundingBox(0.60, 0.19, 0.30, 0.08),
                        DocumentBlockRole.FORMULA, 12, List.of("System Model"), "", null, null,
                        0.8, DocumentBlockContentMode.REGION)));

        SourceAnchor anchor = service.build(artifact).equations().get(0).definition();

        assertThat(anchor.kind()).isEqualTo(SourceAnchor.Kind.FORMULA_REGION);
        assertThat(anchor.boxes()).hasSize(2);
        assertThat(anchor.bbox().right()).isLessThan(0.60);
    }

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(188L, "a".repeat(64), "parser+semantic-v4", 0.9,
                Instant.parse("2026-08-11T00:00:00Z"), 10, blocks);
    }

    private DocumentBlock block(String id, int page, int order,
                                DocumentBlockRole role, String text) {
        return new DocumentBlock(id, page,
                new NormalizedBoundingBox(0.08, 0.10 + order * 0.01, 0.41, 0.03),
                role, order, List.of("III. Analysis"), text, null, null, 0.9,
                role == DocumentBlockRole.FORMULA
                        ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.TEXT);
    }
}
