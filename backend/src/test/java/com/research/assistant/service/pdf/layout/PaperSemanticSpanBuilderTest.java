package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperSemanticSpanBuilderTest {

    private final PaperSemanticSpanBuilder builder = new PaperSemanticSpanBuilder();

    @Test
    void reconstructsAdjacentLinesWithoutLosingBlockAddresses() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("b1", DocumentBlockRole.BODY, 1, .10, .20, .40, .025,
                        "The proposed receiver jointly de-"),
                block("b2", DocumentBlockRole.BODY, 2, .10, .246, .40, .025,
                        "codes both streams."),
                block("b3", DocumentBlockRole.BODY, 3, .62, .20, .30, .025,
                        "A different column must stay separate.")));

        List<PaperSemanticSpan> spans = builder.build(artifact);

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).text()).isEqualTo(
                "The proposed receiver jointly decodes both streams.");
        assertThat(spans.get(0).blockIds()).containsExactly("b1", "b2");
        assertThat(spans.get(1).blockIds()).containsExactly("b3");
    }

    @Test
    void reconstructsOnlyHyphenatedTextAcrossAColumnBoundary() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("left-tail", DocumentBlockRole.BODY, 1,
                        .08, .72, .38, .025, "The proposed receiver jointly de-"),
                block("right-head", DocumentBlockRole.BODY, 2,
                        .55, .08, .38, .025, "codes both streams.")));

        List<PaperSemanticSpan> spans = builder.build(artifact);

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).text()).isEqualTo(
                "The proposed receiver jointly decodes both streams.");
        assertThat(spans.get(0).blockIds()).containsExactly("left-tail", "right-head");
    }

    @Test
    void doesNotFlattenIndependentColumnSentences() {
        PaperLayoutArtifact artifact = artifact(List.of(
                block("left-tail", DocumentBlockRole.BODY, 1,
                        .08, .72, .38, .025, "The left column ends a complete sentence."),
                block("right-head", DocumentBlockRole.BODY, 2,
                        .55, .08, .38, .025, "The right column starts another sentence.")));

        assertThat(builder.build(artifact)).hasSize(2);
    }

    @Test
    void correctsOnlyObviousFormulaAndCaptionMisclassifications() {
        DocumentBlock prose = block("mail", DocumentBlockRole.FORMULA, 1,
                .10, .20, .80, .03, "Corresponding author email is author@example.org");
        DocumentBlock formula = block("eq", DocumentBlockRole.FORMULA, 2,
                .10, .30, .80, .03, "SINR = P_s / (I + N)");
        DocumentBlock caption = block("fig", DocumentBlockRole.BODY, 3,
                .10, .40, .80, .03, "Fig. 3. BLER comparison under different SNR values.");
        DocumentBlock captionMistake = block("fig-reference", DocumentBlockRole.CAPTION, 4,
                .10, .50, .80, .03, "Fig. 4 exhibits the relationship between ET and K.");

        List<PaperSemanticSpan> spans = builder.build(
                artifact(List.of(prose, formula, caption, captionMistake)));

        assertThat(spans).extracting(PaperSemanticSpan::role)
                .containsExactly(DocumentBlockRole.BODY,
                        DocumentBlockRole.FORMULA, DocumentBlockRole.CAPTION,
                        DocumentBlockRole.BODY);
    }

    @Test
    void continuesAnUnfinishedParagraphAcrossAdjacentPageEdges() {
        PaperLayoutArtifact artifact = artifact(List.of(
                blockOnPage("tail", 1, DocumentBlockRole.BODY, 1,
                        .55, .90, .38, .025, "The receiver jointly de-"),
                blockOnPage("head", 2, DocumentBlockRole.BODY, 2,
                        .08, .08, .40, .025, "codes the common and private streams.")));

        List<PaperSemanticSpan> spans = builder.build(artifact);

        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.text()).isEqualTo(
                    "The receiver jointly decodes the common and private streams.");
            assertThat(span.blockIds()).containsExactly("tail", "head");
            assertThat(span.blocks()).extracting(DocumentBlock::page).containsExactly(1, 2);
        });
    }

    @Test
    void doesNotContinueACompletedSentenceAcrossPages() {
        PaperLayoutArtifact artifact = artifact(List.of(
                blockOnPage("tail", 1, DocumentBlockRole.BODY, 1,
                        .55, .90, .38, .025, "The first experiment is complete."),
                blockOnPage("head", 2, DocumentBlockRole.BODY, 2,
                        .08, .08, .40, .025, "the next experiment changes the channel.")));

        assertThat(builder.build(artifact)).hasSize(2);
    }

    @Test
    void stopsAContinuedParagraphBeforeANumberedReferenceEntry() {
        PaperLayoutArtifact artifact = artifact(List.of(
                blockOnPage("tail", 1, DocumentBlockRole.BODY, 1,
                        .55, .90, .38, .025, "The approximation is obtained by"),
                blockOnPage("head", 2, DocumentBlockRole.BODY, 2,
                        .08, .08, .40, .025, "applying the bound in the previous section"),
                blockOnPage("reference", 2, DocumentBlockRole.BODY, 3,
                        .08, .11, .40, .025, "[17] J. Zhang and J. Andrews, A reference title.")));

        List<PaperSemanticSpan> spans = builder.build(artifact);

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).blockIds()).containsExactly("tail", "head");
        assertThat(spans.get(0).text()).doesNotContain("[17]");
        assertThat(spans.get(1).blockIds()).containsExactly("reference");
    }

    @Test
    void doesNotContinueIntoABlockContainingAnInlineBibliographyEntry() {
        PaperLayoutArtifact artifact = artifact(List.of(
                blockOnPage("tail", 1, DocumentBlockRole.BODY, 1,
                        .55, .90, .38, .025, "The expression is bounded by"),
                blockOnPage("mixed", 2, DocumentBlockRole.BODY, 2,
                        .08, .08, .82, .025,
                        "applying Jensen's inequality [17] J. Zhang and J. Andrews, A reference title.")));

        List<PaperSemanticSpan> spans = builder.build(artifact);

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).blockIds()).containsExactly("tail");
        assertThat(spans.get(1).blockIds()).containsExactly("mixed");
    }

    @Test
    void doesNotJoinCompactMathOrPanelLabelsAcrossPages() {
        PaperLayoutArtifact artifact = artifact(List.of(
                blockOnPage("math-tail", 1, DocumentBlockRole.BODY, 1,
                        .55, .90, .38, .025, "d theta = 4 pi / 9"),
                blockOnPage("panel-head", 2, DocumentBlockRole.BODY, 2,
                        .08, .08, .40, .025, "a")));

        assertThat(builder.build(artifact)).hasSize(2)
                .allSatisfy(span -> assertThat(span.blocks()).hasSize(1));
    }

    @Test
    void dropsStandaloneGlyphsAndShortLayoutTokensFromSemanticSpans() {
        DocumentBlock radical = new DocumentBlock("radical", 1,
                new NormalizedBoundingBox(.24, .08, .02, .03), DocumentBlockRole.BODY, 1,
                List.of("Theorem 1"), "√", null, null, .9,
                DocumentBlockContentMode.TEXT,
                new MathContentProfile(MathContentLevel.LIGHT, .8, 1, List.of(), "test"));
        DocumentBlock prose = block("prose", DocumentBlockRole.BODY, 2,
                .10, .20, .80, .03, "The proposed method improves the achievable rate.");

        List<PaperSemanticSpan> spans = builder.build(artifact(List.of(radical, prose)));

        assertThat(spans).extracting(PaperSemanticSpan::text)
                .containsExactly("The proposed method improves the achievable rate.")
                .doesNotContain("√");
    }

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.parse("2026-01-01T00:00:00Z"), 1, blocks);
    }

    private DocumentBlock block(String id, DocumentBlockRole role, int order,
                                double x, double y, double width, double height, String text) {
        return blockOnPage(id, 1, role, order, x, y, width, height, text);
    }

    private DocumentBlock blockOnPage(String id, int page, DocumentBlockRole role, int order,
                                      double x, double y, double width, double height, String text) {
        return new DocumentBlock(id, page, new NormalizedBoundingBox(x, y, width, height),
                role, order, List.of("Method"), text, null, null, .9);
    }
}
