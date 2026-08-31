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

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.parse("2026-01-01T00:00:00Z"), 1, blocks);
    }

    private DocumentBlock block(String id, DocumentBlockRole role, int order,
                                double x, double y, double width, double height, String text) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, width, height),
                role, order, List.of("Method"), text, null, null, .9);
    }
}
