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
    void formulaAnchorPublishesOneOuterBoxForNearbyFragmentsButNotOtherColumn() {
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
        assertThat(anchor.boxes()).hasSize(1);
        assertThat(anchor.bbox().right()).isLessThan(0.60);
    }

    @Test
    void formulaAnchorIncludesMathDenseBodyFragmentsButExcludesFollowingProse() {
        MathContentProfile math = new MathContentProfile(
                MathContentLevel.LIGHT, .35, 2, List.of(), "test");
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("formula-main", 6, new NormalizedBoundingBox(.54, .60, .34, .02),
                        DocumentBlockRole.BODY, 10, List.of("Theorem 2"),
                        "Rk(t) = log(1+x) - log(1+y)", null, null, .9,
                        DocumentBlockContentMode.TEXT, math),
                new DocumentBlock("formula-tail", 6, new NormalizedBoundingBox(.60, .64, .28, .02),
                        DocumentBlockRole.FORMULA, 11, List.of("Theorem 2"),
                        "- Q(beta). (31)", null, null, .9, DocumentBlockContentMode.REGION),
                new DocumentBlock("prose", 6, new NormalizedBoundingBox(.52, .67, .38, .03),
                        DocumentBlockRole.BODY, 12, List.of("Theorem 2"),
                        "where the terms are defined in Lemmas 4, 5 and 6", null, null, .9)));

        SourceAnchor anchor = service.build(artifact).equations().get(0).definition();

        assertThat(anchor.boxes()).hasSize(1);
        assertThat(anchor.targetText()).contains("Rk(t) = log(1+x)", "- Q(beta). (31)")
                .doesNotContain("where the terms");
    }

    @Test
    void assignsEquationsWithinTheirOwnColumnBeforeBuildingUnifiedDocumentOrder() {
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("lemma3", 5, new NormalizedBoundingBox(.52, .60, .40, .03),
                        DocumentBlockRole.BODY, 10, List.of("III. Analysis"),
                        "Lemma 3. The CDF is equivalent to", null, null, .9),
                new DocumentBlock("eq12", 5, new NormalizedBoundingBox(.10, .64, .35, .04),
                        DocumentBlockRole.FORMULA, 11, List.of("III. Analysis"),
                        "F(x) = 1 - exp(-x). (12)", null, null, .9, DocumentBlockContentMode.REGION),
                new DocumentBlock("eq18", 5, new NormalizedBoundingBox(.59, .65, .32, .04),
                        DocumentBlockRole.FORMULA, 12, List.of("III. Analysis"),
                        "C1 = integral f(x). (18)", null, null, .9, DocumentBlockContentMode.REGION)));

        PaperSourceIndex index = service.build(artifact);

        EquationEntity left = index.equations().stream().filter(item -> item.number().equals("12")).findFirst().orElseThrow();
        EquationEntity right = index.equations().stream().filter(item -> item.number().equals("18")).findFirst().orElseThrow();
        assertThat(left.relation()).isEqualTo(EquationEntity.Relation.OTHER);
        assertThat(right.statementKind()).isEqualTo("LEMMA");
        assertThat(right.theoremNumber()).isEqualTo("3");
    }

    @Test
    void doesNotCarryAStatementOwnerAcrossANewSectionOnTheNextPage() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("theorem", 6, 10, DocumentBlockRole.BODY,
                        "Theorem 2. The lower bound is"),
                block("section", 7, 11, DocumentBlockRole.HEADING,
                        "IV. Problem Formulation and Solution"),
                block("eq35", 7, 12, DocumentBlockRole.FORMULA,
                        "P0 = max sum rate. (35)")));

        EquationEntity equation = service.build(artifact).equations().get(0);

        assertThat(equation.number()).isEqualTo("35");
        assertThat(equation.relation()).isEqualTo(EquationEntity.Relation.OTHER);
        assertThat(equation.statementKind()).isBlank();
    }

    @Test
    void continuesLogicalReadingOrderAcrossPageColumnBoundaries() {
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("theorem-right", 6,
                        new NormalizedBoundingBox(.55, .75, .38, .03),
                        DocumentBlockRole.BODY, 20, List.of("III. Analysis"),
                        "Theorem 2. The private-stream lower bound is", null, null, .9),
                new DocumentBlock("eq31-left", 7,
                        new NormalizedBoundingBox(.08, .08, .40, .04),
                        DocumentBlockRole.FORMULA, 21, List.of("III. Analysis"),
                        "Rk = C(Gamma) - Q(beta). (31)", null, null, .9,
                        DocumentBlockContentMode.REGION)));

        EquationEntity equation = service.build(artifact).equations().get(0);

        assertThat(equation.statementLabel()).isEqualTo("Theorem 2");
        assertThat(equation.relation()).isEqualTo(EquationEntity.Relation.THEOREM_RESULT);
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
