package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
    void keepsTheActualDisplayEquationWhenLaterProseMentionsTheSameNumberWithAnOperator() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("eq12", 3, 10, DocumentBlockRole.FORMULA,
                        "G = (1 - m) r_i (1 - epsilon). (12)"),
                block("mention12", 5, 20, DocumentBlockRole.BODY,
                        "r_i = 2 tau / (m - nE) as epsilon is small in (12).")));

        EquationEntity equation = service.build(artifact).equations().stream()
                .filter(item -> item.number().equals("12")).findFirst().orElseThrow();

        assertThat(equation.definition().blockId()).isEqualTo("eq12");
        assertThat(equation.mentions()).extracting(SourceAnchor::blockId).contains("mention12");
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
    void formulaAnchorUsesTheCompleteNumberedDisplayContext() {
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("line-1", 5, new NormalizedBoundingBox(.58, .30, .32, .02),
                        DocumentBlockRole.FORMULA, 10, List.of("Model"),
                        "a = b +", null, null, .88, DocumentBlockContentMode.REGION,
                        null, DocumentLayoutLane.RIGHT),
                new DocumentBlock("line-2", 5, new NormalizedBoundingBox(.60, .35, .28, .02),
                        DocumentBlockRole.FORMULA, 11, List.of("Model"),
                        "c - d +", null, null, .88, DocumentBlockContentMode.REGION,
                        null, DocumentLayoutLane.RIGHT),
                new DocumentBlock("line-3", 5, new NormalizedBoundingBox(.58, .41, .32, .02),
                        DocumentBlockRole.FORMULA, 12, List.of("Model"),
                        "e = f. (10)", null, null, .88, DocumentBlockContentMode.REGION,
                        null, DocumentLayoutLane.RIGHT)));

        SourceAnchor anchor = service.build(artifact).equations().get(0).definition();

        assertThat(anchor.targetText()).contains("a = b +", "c - d +", "e = f. (10)");
        assertThat(anchor.bbox().y()).isLessThan(.30);
        assertThat(anchor.bbox().bottom()).isGreaterThan(.43);
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
    void formulaAnchorIncludesTheLowestRowOfAFractionLikeDisplayEquation() {
        MathContentProfile math = new MathContentProfile(
                MathContentLevel.LIGHT, .30, 3, List.of(), "test");
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("formula-main", 4, new NormalizedBoundingBox(.13, .682, .35, .008),
                        DocumentBlockRole.FORMULA, 125, List.of("SINR"),
                        "Rbar = B log(1 + fraction). (6)", null, null, .88,
                        DocumentBlockContentMode.REGION, null, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula-sum", 4, new NormalizedBoundingBox(.308, .675, .018, .024),
                        DocumentBlockRole.BODY, 124, List.of("SINR"), "sum", null, null, .88,
                        DocumentBlockContentMode.TEXT, math, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula-denominator", 4,
                        new NormalizedBoundingBox(.144, .686, .242, .035),
                        DocumentBlockRole.BODY, 126, List.of("SINR"),
                        "n,k 2 k-1 g n,k p n,j + 1 j=1", null, null, .88,
                        DocumentBlockContentMode.TEXT, math, DocumentLayoutLane.LEFT),
                block("following-prose", 4, 130, DocumentBlockRole.BODY,
                        "The achievable throughput is then evaluated.")));

        SourceAnchor anchor = service.build(artifact).equations().stream()
                .filter(equation -> equation.number().equals("6"))
                .findFirst().orElseThrow().definition();

        assertThat(anchor.bbox().bottom()).isGreaterThan(.72);
        assertThat(anchor.targetText()).contains("n,k 2 k-1 g n,k p n,j + 1 j=1");
    }

    @Test
    void formulaAnchorFallsBackToNearbyComponentsWhenAnOverlappingLeadInSplitsTheContext() {
        MathContentProfile math = new MathContentProfile(
                MathContentLevel.LIGHT, .35, 2, List.of(), "test");
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("lead-in", 5, new NormalizedBoundingBox(.06, .30, .34, .035),
                        DocumentBlockRole.BODY, 10, List.of("Model"),
                        "as follows: pseudo inverse components", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT),
                new DocumentBlock("formula-number", 5, new NormalizedBoundingBox(.10, .332, .38, .008),
                        DocumentBlockRole.FORMULA, 11, List.of("Model"),
                        "M = m1 / m2 (13)", null, null, .9,
                        DocumentBlockContentMode.REGION, null, DocumentLayoutLane.LEFT),
                new DocumentBlock("denominator", 5, new NormalizedBoundingBox(.14, .342, .28, .025),
                        DocumentBlockRole.BODY, 12, List.of("Model"),
                        "H m1 m2", null, null, .9,
                        DocumentBlockContentMode.TEXT, math, DocumentLayoutLane.LEFT)));

        SourceAnchor anchor = service.build(artifact).equations().stream()
                .filter(equation -> equation.number().equals("13"))
                .findFirst().orElseThrow().definition();

        assertThat(anchor.bbox().bottom()).isGreaterThan(.367);
        assertThat(anchor.targetText()).contains("H m1 m2");
    }

    @Test
    void doesNotShareSplitRowsBetweenAdjacentNumberedFormulas() {
        MathContentProfile math = new MathContentProfile(
                MathContentLevel.LIGHT, .35, 2, List.of(), "test");
        PaperLayoutArtifact artifact = artifact(List.of(
                new DocumentBlock("formula12", 5,
                        new NormalizedBoundingBox(.14, .249, .342, .008),
                        DocumentBlockRole.FORMULA, 172, List.of("Model"),
                        "Hbar = [Hbar Hbar Hbar ... Hbar]. (12)", null, null, .9,
                        DocumentBlockContentMode.REGION, null, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula12-subscripts", 5,
                        new NormalizedBoundingBox(.195, .255, .174, .021),
                        DocumentBlockRole.BODY, 173, List.of("Model"),
                        "1 2 3 N N×N", null, null, .9,
                        DocumentBlockContentMode.TEXT, math, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula12-prose", 5,
                        new NormalizedBoundingBox(.063, .272, .419, .024),
                        DocumentBlockRole.BODY, 174, List.of("Model"),
                        "Finally, the matrix is used in the next step.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT),
                new DocumentBlock("formula13-bracket", 5,
                        new NormalizedBoundingBox(.217, .298, .216, .024),
                        DocumentBlockRole.FORMULA, 175, List.of("Model"),
                        "[ ]", null, null, .9,
                        DocumentBlockContentMode.REGION, null, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula13-prose", 5,
                        new NormalizedBoundingBox(.063, .304, .342, .029),
                        DocumentBlockRole.BODY, 176, List.of("Model"),
                        "as follows: pseudo inverse components", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT),
                new DocumentBlock("formula13", 5,
                        new NormalizedBoundingBox(.099, .332, .382, .008),
                        DocumentBlockRole.FORMULA, 177, List.of("Model"),
                        "M = = ... (13)", null, null, .9,
                        DocumentBlockContentMode.REGION, null, DocumentLayoutLane.LEFT),
                new DocumentBlock("formula13-denominator", 5,
                        new NormalizedBoundingBox(.141, .341, .279, .010),
                        DocumentBlockRole.BODY, 178, List.of("Model"),
                        "H m1 m2 mN", null, null, .9,
                        DocumentBlockContentMode.TEXT, math, DocumentLayoutLane.LEFT)));

        PaperSourceIndex index = service.build(artifact);
        EquationEntity formula12 = index.equations().stream()
                .filter(equation -> equation.number().equals("12"))
                .findFirst().orElseThrow();
        EquationEntity formula13 = index.equations().stream()
                .filter(equation -> equation.number().equals("13"))
                .findFirst().orElseThrow();

        assertThat(formula12.definition().targetText())
                .contains("Hbar =", "N×N")
                .doesNotContain("[ ]");
        assertThat(formula13.definition().targetText())
                .contains("[ ]", "M =")
                .doesNotContain("N×N");
        assertThat(formula12.definition().bbox().bottom())
                .isLessThan(formula13.definition().bbox().y());
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

    @Test
    void groupsNumberedFormulaFamilyWithoutAbsorbingFollowingProse() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("eq35a", 8, 10, DocumentBlockRole.FORMULA, "x >= 0. (35a)"),
                block("eq35b", 8, 11, DocumentBlockRole.FORMULA, "y <= 1. (35b)"),
                block("eq35c", 8, 12, DocumentBlockRole.FORMULA, "x + y = 1. (35c)"),
                block("prose", 8, 13, DocumentBlockRole.BODY,
                        "These constraints define the feasible allocation region.")));

        PaperSourceUnit family = service.build(artifact).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FORMULA_FAMILY)
                .findFirst().orElseThrow();

        assertThat(family.label()).isEqualTo("Equations (35a–35c)");
        assertThat(family.blocks()).extracting(DocumentBlock::id)
                .containsExactly("eq35a", "eq35b", "eq35c");
        assertThat(family.text()).contains("(35a)", "(35b)", "(35c)")
                .doesNotContain("feasible allocation");
    }

    @Test
    void groupsAlgorithmAndVisualWithOnlyDirectlyRelatedText() {
        List<DocumentBlock> blocks = List.of(
                block("algorithm", 3, 1, DocumentBlockRole.BODY, "Algorithm 1 Iterative allocation"),
                block("step1", 3, 2, DocumentBlockRole.BODY, "1. Initialize the power vector."),
                block("step2", 3, 3, DocumentBlockRole.BODY, "2. Update the common stream."),
                block("next-section", 3, 4, DocumentBlockRole.HEADING, "IV. Results"),
                new DocumentBlock("figure", 4, new NormalizedBoundingBox(.08, .20, .40, .25),
                        DocumentBlockRole.FIGURE, 5, List.of("Results"), "", null, null, .9),
                new DocumentBlock("caption", 4, new NormalizedBoundingBox(.08, .46, .40, .03),
                        DocumentBlockRole.CAPTION, 6, List.of("Results"),
                        "Fig. 2: Convergence of the proposed method.", null, null, .9),
                new DocumentBlock("explanation", 4, new NormalizedBoundingBox(.08, .50, .40, .03),
                        DocumentBlockRole.BODY, 7, List.of("Results"),
                        "Fig. 2 shows that the method converges rapidly.", null, null, .9),
                new DocumentBlock("unrelated", 4, new NormalizedBoundingBox(.08, .60, .40, .03),
                        DocumentBlockRole.BODY, 8, List.of("Results"),
                        "The next experiment changes the channel model.", null, null, .9),
                new DocumentBlock("table", 5, new NormalizedBoundingBox(.08, .20, .84, .20),
                        DocumentBlockRole.TABLE, 9, List.of("Results"), "", null,
                        "Method Accuracy A 91 B 88", .9),
                new DocumentBlock("table-caption", 5, new NormalizedBoundingBox(.08, .41, .84, .03),
                        DocumentBlockRole.CAPTION, 10, List.of("Results"),
                        "Table II: Accuracy under different methods.", null, null, .9),
                new DocumentBlock("table-explanation", 5, new NormalizedBoundingBox(.08, .45, .84, .03),
                        DocumentBlockRole.BODY, 11, List.of("Results"),
                        "Table II lists the accuracy of every method.", null, null, .9));

        List<PaperSourceUnit> units = service.build(artifact(blocks)).sourceUnits();
        PaperSourceUnit algorithm = units.stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM).findFirst().orElseThrow();
        PaperSourceUnit figure = units.stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE).findFirst().orElseThrow();
        PaperSourceUnit table = units.stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.TABLE).findFirst().orElseThrow();

        assertThat(algorithm.blocks()).extracting(DocumentBlock::id)
                .containsExactly("algorithm", "step1", "step2");
        assertThat(figure.blocks()).extracting(DocumentBlock::id)
                .containsExactly("figure", "caption")
                .doesNotContain("explanation", "unrelated");
        assertThat(figure.boxes()).hasSize(2);
        assertThat(table.blocks()).extracting(DocumentBlock::id)
                .containsExactly("table", "table-caption", "table-explanation");
    }

    @Test
    void keepsALongSameLaneAlgorithmUntilItsStructuralBoundary() {
        List<DocumentBlock> blocks = new java.util.ArrayList<>();
        blocks.add(new DocumentBlock("algorithm", 3,
                new NormalizedBoundingBox(.08, .08, .40, .03), DocumentBlockRole.BODY, 1,
                List.of("Method"), "Algorithm 8 Iterative allocation", null, null, .9,
                DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT));
        for (int index = 1; index <= 20; index++) {
            blocks.add(new DocumentBlock("step-" + index, 3,
                    new NormalizedBoundingBox(.08, .12 + index * .034, .40, .025),
                    DocumentBlockRole.BODY, index + 1, List.of("Method"),
                    index + ". Update the allocation variables.", null, null, .9,
                    DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT));
        }
        blocks.add(new DocumentBlock("next-section", 3,
                new NormalizedBoundingBox(.08, .84, .40, .03), DocumentBlockRole.HEADING, 23,
                List.of("Results"), "IV. Results", null, null, .9,
                DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT));

        PaperSourceUnit algorithm = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM).findFirst().orElseThrow();

        assertThat(algorithm.blocks()).extracting(DocumentBlock::id)
                .hasSize(21)
                .contains("step-20")
                .doesNotContain("next-section");
    }

    @Test
    void stopsLongAlgorithmBeforeSeparatedNarrativeInTheSameLane() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock("algorithm", 7, new NormalizedBoundingBox(.08, .10, .40, .025),
                        DocumentBlockRole.BODY, 1, List.of("Method"),
                        "Algorithm 2 User clustering", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT),
                new DocumentBlock("step-1", 7, new NormalizedBoundingBox(.08, .14, .40, .025),
                        DocumentBlockRole.BODY, 2, List.of("Method"),
                        "1. Initialize the user sets.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT),
                new DocumentBlock("step-2", 7, new NormalizedBoundingBox(.08, .18, .40, .025),
                        DocumentBlockRole.BODY, 3, List.of("Method"),
                        "2. Update the clusters and end.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""), DocumentLayoutLane.LEFT),
                new DocumentBlock("narrative", 7, new NormalizedBoundingBox(.08, .25, .40, .045),
                        DocumentBlockRole.BODY, 4, List.of("Method"),
                        "The following paragraph explains why the resulting user clusters improve performance.",
                        null, null, .9, DocumentBlockContentMode.TEXT,
                        MathContentProfile.none(""), DocumentLayoutLane.LEFT));

        PaperSourceUnit algorithm = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM)
                .findFirst().orElseThrow();

        assertThat(algorithm.blocks()).extracting(DocumentBlock::id)
                .containsExactly("algorithm", "step-1", "step-2")
                .doesNotContain("narrative");
    }

    @Test
    void excludesAFullWidthCrossColumnExtractionArtifactFromAlgorithmSource() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock("algorithm", 3, new NormalizedBoundingBox(.08, .20, .40, .03),
                        DocumentBlockRole.BODY, 1, List.of("Method"),
                        "Algorithm 2 User clustering", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT),
                new DocumentBlock("step1", 3, new NormalizedBoundingBox(.08, .24, .40, .03),
                        DocumentBlockRole.BODY, 2, List.of("Method"),
                        "1. Initialize the user sets.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT),
                new DocumentBlock("mixed", 3, new NormalizedBoundingBox(.08, .28, .84, .04),
                        DocumentBlockRole.BODY, 3, List.of("Method"),
                        "2. Select users. Right-column prose must not enter this algorithm.",
                        null, null, .9, DocumentBlockContentMode.TEXT,
                        MathContentProfile.none(""), DocumentLayoutLane.FULL),
                new DocumentBlock("step2", 3, new NormalizedBoundingBox(.08, .31, .40, .03),
                        DocumentBlockRole.BODY, 4, List.of("Method"),
                        "2. Update the selected cluster.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT));

        PaperSourceUnit algorithm = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM)
                .findFirst().orElseThrow();

        assertThat(algorithm.blocks()).extracting(DocumentBlock::id)
                .containsExactly("algorithm", "step1", "step2")
                .doesNotContain("mixed");
        assertThat(algorithm.text()).doesNotContain("Right-column prose");
    }

    @Test
    void addsCaptionAnchoredFigureRegionWhenVectorFigureHasNoFigureBlock() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock("prose", 4, new NormalizedBoundingBox(.06, .10, .42, .08),
                        DocumentBlockRole.BODY, 1, List.of("Results"),
                        "The following experiment compares the achievable rates under several schemes.",
                        null, null, .9, DocumentBlockContentMode.TEXT,
                        MathContentProfile.none(""), DocumentLayoutLane.LEFT),
                new DocumentBlock("caption", 4, new NormalizedBoundingBox(.06, .44, .42, .04),
                        DocumentBlockRole.CAPTION, 2, List.of("Results"),
                        "Fig. 3. Achievable rate comparison.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.LEFT));

        PaperSourceUnit figure = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE)
                .findFirst().orElseThrow();

        assertThat(figure.text()).isEqualTo("Fig. 3. Achievable rate comparison.");
        assertThat(figure.blocks()).extracting(DocumentBlock::id)
                .containsExactly("caption:visual-region", "caption");
        DocumentBlock region = figure.blocks().get(0);
        assertThat(region.role()).isEqualTo(DocumentBlockRole.FIGURE);
        assertThat(region.contentMode()).isEqualTo(DocumentBlockContentMode.REGION);
        assertThat(region.bbox().height()).isGreaterThan(.20);
        assertThat(region.bbox().right()).isLessThanOrEqualTo(.49);
    }

    @Test
    void prefersProceduralAlgorithmObjectOverNarrativeMentionWithSameLabel() {
        List<DocumentBlock> blocks = List.of(
                block("mention", 3, 1, DocumentBlockRole.BODY,
                        "Algorithm 1 is discussed in the following section."),
                block("algorithm", 3, 2, DocumentBlockRole.BODY,
                        "Algorithm 1 Iterative allocation"),
                block("step1", 3, 3, DocumentBlockRole.BODY,
                        "1. Initialize the power vector."),
                block("step2", 3, 4, DocumentBlockRole.BODY,
                        "2. Update the common stream."));

        List<PaperSourceUnit> algorithms = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM).toList();

        assertThat(algorithms).singleElement().satisfies(unit ->
                assertThat(unit.blocks()).extracting(DocumentBlock::id)
                        .containsExactly("algorithm", "step1", "step2"));
    }

    @Test
    void recognizesCaptionWithoutPunctuationButRejectsNarrativeReference() {
        List<DocumentBlock> blocks = List.of(
                block("caption", 4, 1, DocumentBlockRole.CAPTION,
                        "Fig. 7 Two-user achievable rate region"),
                block("reference", 4, 2, DocumentBlockRole.BODY,
                        "Fig. 7 shows the achievable rate of both users."));

        List<PaperSourceUnit> figures = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE).toList();

        assertThat(figures).singleElement().satisfies(unit -> {
            assertThat(unit.label()).isEqualTo("Fig. 7");
            assertThat(unit.blocks()).extracting(DocumentBlock::id)
                    .contains("caption")
                    .doesNotContain("reference");
        });
    }

    @Test
    void rebuildsWrappedFigureCaptionAcrossBodyAndFormulaBlocks() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock("caption", 4, new NormalizedBoundingBox(.52, .28, .41, .012),
                        DocumentBlockRole.CAPTION, 1, List.of("Results"),
                        "FIGURE 2. Spectral efficiency for N = 3", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.RIGHT),
                new DocumentBlock("caption-line-2", 4, new NormalizedBoundingBox(.52, .296, .40, .012),
                        DocumentBlockRole.BODY, 2, List.of("Results"),
                        "and R = OMA throughput with 50% bandwidth.", null, null, .9,
                        DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.RIGHT),
                new DocumentBlock("caption-line-3", 4, new NormalizedBoundingBox(.52, .312, .36, .012),
                        DocumentBlockRole.FORMULA, 3, List.of("Results"),
                        "The cluster-heads are distributed within 150m of the BS.", null, null, .9,
                        DocumentBlockContentMode.REGION, MathContentProfile.none(""),
                        DocumentLayoutLane.RIGHT),
                new DocumentBlock("analysis", 4, new NormalizedBoundingBox(.52, .36, .41, .08),
                        DocumentBlockRole.BODY, 4, List.of("Results"),
                        "Figure 2 shows that the proposed method improves spectral efficiency.",
                        null, null, .9, DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                        DocumentLayoutLane.RIGHT));

        PaperSourceUnit figure = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE)
                .findFirst().orElseThrow();

        assertThat(figure.text()).contains("FIGURE 2", "50% bandwidth", "distributed within 150m")
                .doesNotContain("proposed method improves");
        assertThat(figure.blocks()).extracting(DocumentBlock::id)
                .contains("caption", "caption-line-2", "caption-line-3")
                .doesNotContain("analysis");
    }

    @Test
    void rejectsMultiFigureNarrativeReferenceAsCaption() {
        List<DocumentBlock> blocks = List.of(
                block("reference", 4, 1, DocumentBlockRole.CAPTION,
                        "Fig. 5 and Fig. 6, respectively. In Figs. 5-6, the cluster-heads improve."),
                block("caption", 4, 2, DocumentBlockRole.CAPTION,
                        "FIGURE 5. Spectral efficiency of the proposed method."));

        List<PaperSourceUnit> figures = service.build(artifact(blocks)).sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE).toList();

        assertThat(figures).singleElement().satisfies(unit -> {
            assertThat(unit.label()).isEqualTo("FIGURE 5");
            assertThat(unit.blocks()).extracting(DocumentBlock::id)
                    .contains("caption")
                    .doesNotContain("reference");
        });
    }

    @Test
    void keepsCaptionStyleAlgorithmTitleWhenBodyIsGraphical() {
        PaperSourceUnit algorithm = service.build(artifact(List.of(
                        block("algorithm-title", 3, 1, DocumentBlockRole.CAPTION,
                                "Algorithm 4: One-dimensional SCA."))))
                .sourceUnits().stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.ALGORITHM)
                .findFirst().orElseThrow();

        assertThat(algorithm.label()).isEqualTo("Algorithm 4");
        assertThat(algorithm.blocks()).extracting(DocumentBlock::id)
                .containsExactly("algorithm-title");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RA_LAYOUT_SAMPLE", matches = ".+")
    void auditsSourceUnitsOnConfiguredRealPaper() {
        File sample = new File(System.getenv("RA_LAYOUT_SAMPLE"));
        long started = System.nanoTime();
        PaperLayoutArtifact raw = new PdfBoxPaperLayoutParser().parse(999L, sample);
        long parsed = System.nanoTime();
        LayoutQualityReport quality = new PaperLayoutQualityAssessor().assess(raw);
        PaperLayoutArtifact enriched = new PaperMathContentEnricher().enrich(
                new PaperLayoutSemanticEnricher().enrich(raw, PaperLayoutHints.empty()));
        long enrichmentFinished = System.nanoTime();

        PaperSourceIndex index = service.build(enriched);
        List<PaperSemanticSpan> crossPageSpans = new PaperSemanticSpanBuilder().build(enriched).stream()
                .filter(span -> span.blocks().stream().map(DocumentBlock::page).distinct().count() > 1)
                .toList();
        long finished = System.nanoTime();

        assertThat(quality.score()).isGreaterThan(.8);
        if (quality.fallbackRecommended()) {
            assertThat(quality.issues()).contains(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        } else {
            assertThat(quality.readingOrderScore()).isGreaterThanOrEqualTo(.92);
        }
        assertThat(raw.blocks()).extracting(DocumentBlock::readingOrder)
                .containsExactlyElementsOf(IntStream.range(0, raw.blocks().size()).boxed().toList());
        assertThat(raw.blocks()).allSatisfy(block ->
                assertThat(block.layoutLane()).isNotEqualTo(DocumentLayoutLane.UNKNOWN));
        printOrderAnomalies(raw);
        assertThat(index.sourceUnits()).allSatisfy(unit -> {
            assertThat(unit.blocks()).isNotEmpty().allSatisfy(block ->
                    assertThat(block.page()).isEqualTo(unit.page()));
            assertThat(unit.boxes()).isNotEmpty();
            assertThat(unit.text()).isNotBlank();
        });
        assertThat(index.continuations()).allSatisfy(continuation -> {
            assertThat(continuation.parts()).extracting(PaperSourceUnit::kind)
                    .containsOnly(continuation.kind());
            for (int part = 1; part < continuation.parts().size(); part++) {
                assertThat(continuation.parts().get(part).page())
                        .isEqualTo(continuation.parts().get(part - 1).page() + 1);
            }
        });
        Map<PaperSourceUnit.Kind, Long> counts = index.sourceUnits().stream()
                .collect(java.util.stream.Collectors.groupingBy(PaperSourceUnit::kind,
                        java.util.stream.Collectors.counting()));
        System.out.printf("PIPELINE_PERF file=%s pages=%d blocks=%d quality=%.3f order=%.3f "
                        + "parseMs=%d enrichMs=%d sourceMs=%d totalMs=%d%n",
                sample.getName(), raw.pageCount(), raw.blocks().size(), quality.score(),
                quality.readingOrderScore(), millis(started, parsed),
                millis(parsed, enrichmentFinished), millis(enrichmentFinished, finished),
                millis(started, finished));
        System.out.printf("SOURCE_UNITS equations=%d units=%d counts=%s%n",
                index.equations().size(), index.sourceUnits().size(), counts);
        index.sourceUnits().forEach(unit -> System.out.printf(
                "  UNIT kind=%s page=%d label=%s blocks=%d chars=%d%n",
                unit.kind(), unit.page(), unit.label(), unit.blocks().size(), unit.text().length()));
        index.continuations().forEach(continuation -> System.out.printf(
                "  CONTINUATION kind=%s label=%s pages=%s parts=%d chars=%d%n",
                continuation.kind(), continuation.label(),
                continuation.parts().stream().map(part -> Integer.toString(part.page())).toList(),
                continuation.parts().size(), continuation.text().length()));
        crossPageSpans.forEach(span -> System.out.printf(
                "  CROSS_PAGE_SPAN pages=%s blocks=%s chars=%d text=%s%n",
                span.blocks().stream().map(DocumentBlock::page).distinct().toList(),
                span.blockIds(), span.text().length(), crossPageBoundary(span)));
    }

    @Test
    void linksConsecutiveFormulaFamilyPartsAcrossPages() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("eq43a", 7, 10, DocumentBlockRole.FORMULA, "x >= 0. (43a)"),
                block("eq43b", 7, 11, DocumentBlockRole.FORMULA, "y >= 0. (43b)"),
                block("eq43c", 8, 12, DocumentBlockRole.FORMULA, "x <= 1. (43c)"),
                block("eq43d", 8, 13, DocumentBlockRole.FORMULA, "y <= 1. (43d)")));

        PaperSourceContinuation continuation = service.build(artifact).continuations().stream()
                .filter(item -> item.kind() == PaperSourceUnit.Kind.FORMULA_FAMILY)
                .findFirst().orElseThrow();

        assertThat(continuation.label()).isEqualTo("Equations (43a–43d)");
        assertThat(continuation.parts()).extracting(PaperSourceUnit::page).containsExactly(7, 8);
        assertThat(continuation.text()).contains("(43a)", "(43b)", "(43c)", "(43d)");
    }

    @Test
    void continuesPageBottomAlgorithmAtNextPageTop() {
        List<DocumentBlock> blocks = List.of(
                new DocumentBlock("algorithm", 1, new NormalizedBoundingBox(.08, .86, .40, .03),
                        DocumentBlockRole.BODY, 1, List.of("Method"),
                        "Algorithm 3 Alternating optimization", null, null, .9),
                new DocumentBlock("step-1", 1, new NormalizedBoundingBox(.08, .90, .40, .03),
                        DocumentBlockRole.BODY, 2, List.of("Method"),
                        "1. Initialize the variables.", null, null, .9),
                new DocumentBlock("step-2", 2, new NormalizedBoundingBox(.08, .08, .40, .03),
                        DocumentBlockRole.BODY, 3, List.of("Method"),
                        "2. Solve the convex subproblem.", null, null, .9),
                new DocumentBlock("step-3", 2, new NormalizedBoundingBox(.08, .12, .40, .03),
                        DocumentBlockRole.BODY, 4, List.of("Method"),
                        "3. Update the beamformer.", null, null, .9),
                new DocumentBlock("section", 2, new NormalizedBoundingBox(.08, .25, .40, .03),
                        DocumentBlockRole.HEADING, 5, List.of("Results"),
                        "IV. Results", null, null, .9));

        PaperSourceContinuation continuation = service.build(artifact(blocks)).continuations().stream()
                .filter(item -> item.kind() == PaperSourceUnit.Kind.ALGORITHM)
                .findFirst().orElseThrow();

        assertThat(continuation.parts()).extracting(PaperSourceUnit::page).containsExactly(1, 2);
        assertThat(continuation.text()).contains("Algorithm 3", "convex subproblem", "beamformer")
                .doesNotContain("IV. Results");
    }

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(188L, "a".repeat(64), "parser+semantic-v4", 0.9,
                Instant.parse("2026-08-11T00:00:00Z"), 10, blocks);
    }

    private String crossPageBoundary(PaperSemanticSpan span) {
        for (int index = 1; index < span.blocks().size(); index++) {
            DocumentBlock previous = span.blocks().get(index - 1);
            DocumentBlock current = span.blocks().get(index);
            if (previous.page() != current.page()) {
                String left = previous.text().replaceAll("\\s+", " ");
                String right = current.text().replaceAll("\\s+", " ");
                left = left.substring(Math.max(0, left.length() - 140));
                right = right.substring(0, Math.min(140, right.length()));
                return left + " || " + right;
            }
        }
        return "";
    }

    private long millis(long start, long end) {
        return Math.round((end - start) / 1_000_000.0);
    }

    private void printOrderAnomalies(PaperLayoutArtifact artifact) {
        artifact.blocks().stream().collect(java.util.stream.Collectors.groupingBy(DocumentBlock::page))
                .forEach((page, blocks) -> {
                    List<DocumentBlock> flow = blocks.stream()
                            .filter(block -> block.role() != DocumentBlockRole.HEADER
                                    && block.role() != DocumentBlockRole.FOOTER
                                    && block.role() != DocumentBlockRole.MARGIN_METADATA)
                            .filter(block -> block.bbox().width() >= .08)
                            .filter(block -> block.text().codePoints()
                                    .filter(Character::isLetterOrDigit).limit(8).count() >= 8)
                            .sorted(java.util.Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
                    long left = flow.stream().filter(block ->
                            block.layoutLane() == DocumentLayoutLane.LEFT).count();
                    long right = flow.stream().filter(block ->
                            block.layoutLane() == DocumentLayoutLane.RIGHT).count();
                    if (left < 3 || right < 3) return;
                    DocumentBlock previous = null;
                    for (DocumentBlock block : flow) {
                        if (block.layoutLane() == DocumentLayoutLane.FULL) {
                            previous = null;
                            continue;
                        }
                        if (block.layoutLane() != DocumentLayoutLane.LEFT
                                && block.layoutLane() != DocumentLayoutLane.RIGHT) continue;
                        if (previous != null
                                && previous.layoutLane() == DocumentLayoutLane.RIGHT
                                && block.layoutLane() == DocumentLayoutLane.LEFT
                                && block.bbox().y() <= previous.bbox().bottom() + .005) {
                            System.out.printf("ORDER_ANOMALY page=%d from=%s[%s] to=%s[%s]%n",
                                    page, previous.id(), shortText(previous.text()),
                                    block.id(), shortText(block.text()));
                        }
                        previous = block;
                    }
                });
    }

    private String shortText(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(90, normalized.length()));
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
