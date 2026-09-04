package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FormulaContextBuilderTest {

    private final FormulaContextBuilder builder = new FormulaContextBuilder();

    @Test
    void groupsWidelySpacedLinesOfOneNumberedDisplayFormula() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("line-1", 1, .65, .30, .22, "a = b +", DocumentLayoutLane.RIGHT),
                formula("line-2", 2, .65, .35, .28, "c - d +", DocumentLayoutLane.RIGHT),
                formula("line-3", 3, .65, .41, .24, "e = f. (10)", DocumentLayoutLane.RIGHT),
                formula("next", 4, .65, .51, .20, "g = h. (11)", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).equationNumbers()).containsExactly("10");
        assertThat(contexts.get(0).blocks()).extracting(DocumentBlock::id)
                .containsExactly("line-1", "line-2", "line-3");
        assertThat(contexts.get(0).bbox().y()).isEqualTo(.30);
        assertThat(contexts.get(0).bbox().bottom()).isEqualTo(.43);
        assertThat(contexts.get(1).equationNumbers()).containsExactly("11");
    }

    @Test
    void keepsIndependentNumberedFormulasAndColumnsSeparate() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("left", 1, .08, .30, .30, "x = y. (10)", DocumentLayoutLane.LEFT),
                formula("right", 2, .60, .30, .30, "u = v. (11)", DocumentLayoutLane.RIGHT),
                formula("next", 3, .60, .37, .30, "p = q. (12)", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        assertThat(contexts).hasSize(3);
        assertThat(contexts).extracting(FormulaContextBuilder.FormulaContext::equationNumbers)
                .containsExactly(List.of("10"), List.of("11"), List.of("12"));
    }

    @Test
    void leavesUnnumberedFormulaFragmentsConservativeWhenSeparated() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("fragment-a", 1, .65, .30, .20, "[]", DocumentLayoutLane.RIGHT),
                formula("fragment-b", 2, .72, .32, .18, "[]", DocumentLayoutLane.RIGHT),
                formula("separate", 3, .65, .43, .20, "[]", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).blocks()).extracting(DocumentBlock::id)
                .containsExactly("fragment-a", "fragment-b");
        assertThat(contexts.get(1).blocks()).extracting(DocumentBlock::id)
                .containsExactly("separate");
    }

    @Test
    void groupsAFullWidthDisplayThatEndsInAnotherLane() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("full-top", 10, .22, .20, .56, "A + B", DocumentLayoutLane.FULL),
                formula("full-middle", 11, .22, .25, .56, "= C + D", DocumentLayoutLane.FULL),
                formula("right-tail", 12, .50, .30, .40, "= E + F. (10)", DocumentLayoutLane.RIGHT),
                formula("next-equation", 13, .60, .42, .30, "G = H. (11)", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        FormulaContextBuilder.FormulaContext first = contexts.stream()
                .filter(context -> context.equationNumbers().contains("10"))
                .findFirst().orElseThrow();
        assertThat(first.blocks()).extracting(DocumentBlock::id)
                .containsExactly("full-top", "full-middle", "right-tail");
        assertThat(contexts).extracting(FormulaContextBuilder.FormulaContext::equationNumbers)
                .contains(List.of("11"));
    }

    @Test
    void doesNotCrossAProseLineWhenFindingTheNextFormulaFragment() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("numbered", 1, .20, .20, .30, "x = y. (10)", DocumentLayoutLane.RIGHT),
                body("prose", 2, .20, .245, .30, "where this begins", DocumentLayoutLane.RIGHT),
                formula("after-prose", 3, .20, .285, .30, "u = v", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        FormulaContextBuilder.FormulaContext numbered = contexts.stream()
                .filter(context -> context.equationNumbers().contains("10"))
                .findFirst().orElseThrow();
        assertThat(numbered.blocks()).extracting(DocumentBlock::id)
                .containsExactly("numbered");
    }

    @Test
    void doesNotTreatAFunctionArgumentAsAnEquationNumber() {
        PaperLayoutArtifact artifact = artifact(List.of(
                formula("constant", 1, .20, .20, .30, "x = ln(2)", DocumentLayoutLane.RIGHT)));

        List<FormulaContextBuilder.FormulaContext> contexts = builder.build(artifact);

        assertThat(contexts).singleElement().extracting(FormulaContextBuilder.FormulaContext::equationNumbers)
                .isEqualTo(List.of());
    }

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.parse("2026-09-03T00:00:00Z"), 1, blocks);
    }

    private DocumentBlock formula(String id, int order, double x, double y,
                                  double width, String text, DocumentLayoutLane lane) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, width, .02),
                DocumentBlockRole.FORMULA, order, List.of("Method"), text, null, null, .80,
                DocumentBlockContentMode.REGION, null, lane);
    }

    private DocumentBlock body(String id, int order, double x, double y,
                               double width, String text, DocumentLayoutLane lane) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, width, .02),
                DocumentBlockRole.BODY, order, List.of("Method"), text, null, null, .80,
                DocumentBlockContentMode.TEXT, null, lane);
    }
}
