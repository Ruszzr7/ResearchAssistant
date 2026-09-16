package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PaperLayoutSemanticEnricherTest {

    private final PaperLayoutSemanticEnricher enricher = new PaperLayoutSemanticEnricher();
    private final PaperLayoutEvidencePolicy evidencePolicy = new PaperLayoutEvidencePolicy();

    @Test
    void shouldClassifyMergeAndFilterAcademicLayoutBlocksDeterministically() {
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                9L,
                "a".repeat(64),
                "pdfbox-layout-v1",
                0.9,
                Instant.parse("2026-07-16T00:00:00Z"),
                2,
                List.of(
                        block(0, 1, 0.08, 0.07, 0.70, 0.01, "Journal preprint 2025"),
                        block(1, 1, 0.16, 0.10, 0.68, 0.018, "A Layout-Aware Research"),
                        block(2, 1, 0.34, 0.125, 0.32, 0.018, "Assistant"),
                        block(3, 1, 0.20, 0.165, 0.60, 0.010, "Alice Smith and Bob Chen"),
                        block(4, 1, 0.08, 0.230, 0.40, 0.010, "Abstract-This paper intro-"),
                        block(5, 1, 0.08, 0.244, 0.40, 0.010, "duces a grounded workflow."),
                        block(6, 1, 0.08, 0.720, 0.40, 0.010, "This work was supported by Grant 123."),
                        block(7, 1, 0.08, 0.734, 0.40, 0.010, "Alice Smith is with Example University."),
                        block(8, 1, 0.56, 0.230, 0.30, 0.010, "I. INTRODUCTION"),
                        block(9, 1, 0.55, 0.250, 0.37, 0.010, "We study the problem."),
                        block(10, 1, 0.57, 0.290, 0.35, 0.010, "A new paragraph follows."),
                        block(11, 1, 0.49, 0.965, 0.02, 0.010, "1"),
                        block(12, 2, 0.08, 0.070, 0.70, 0.010, "Journal preprint 2025"),
                        block(13, 2, 0.08, 0.120, 0.25, 0.010, "II. METHOD"),
                        block(14, 2, 0.08, 0.145, 0.38, 0.010, "The pro-"),
                        block(15, 2, 0.08, 0.159, 0.38, 0.010, "posed algorithm works."),
                        block(16, 2, 0.18, 0.200, 0.24, 0.012, "x = y + 1 (1)"),
                        block(17, 2, 0.08, 0.230, 0.24, 0.010, "TABLE I: RESULTS"),
                        block(18, 2, 0.08, 0.250, 0.34, 0.010, "Fig. 1. System architecture"),
                        block(19, 2, 0.08, 0.300, 0.22, 0.010, "REFERENCES"),
                        block(20, 2, 0.08, 0.325, 0.40, 0.010, "[1] A. Author, Example work."),
                        block(21, 2, 0.08, 0.339, 0.40, 0.010, "continued reference."),
                        block(22, 2, 0.49, 0.965, 0.02, 0.010, "2")
                )
        );

        PaperLayoutArtifact enriched = enricher.enrich(raw, new PaperLayoutHints(
                "A Layout-Aware Research Assistant",
                "Alice Smith; Bob Chen",
                "This paper introduces a grounded workflow."
        ));

        assertThat(enriched.parserVersion()).isEqualTo("pdfbox-layout-v1+semantic-v7");
        assertThat(enriched.blocks()).extracting(DocumentBlock::readingOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .range(0, enriched.blocks().size()).boxed().toList());
        assertThat(enriched.blocks().stream().filter(block -> block.role() == DocumentBlockRole.HEADER))
                .hasSize(2);
        assertThat(enriched.blocks().stream().filter(block -> block.role() == DocumentBlockRole.FOOTER))
                .hasSize(2);
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.TITLE);
            assertThat(block.text()).isEqualTo("A Layout-Aware Research Assistant");
        });
        assertThat(enriched.blocks().stream()
                .filter(block -> block.text().contains("Grant 123")
                        || block.text().contains("Example University"))
                .toList())
                .hasSize(2)
                .allMatch(block -> block.role() == DocumentBlockRole.MARGIN_METADATA);
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.AUTHOR);
            assertThat(block.text()).contains("Alice Smith").contains("Bob Chen");
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.ABSTRACT);
            assertThat(block.text()).contains("paper introduces a grounded workflow");
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.BODY);
            assertThat(block.text()).contains("The proposed algorithm works");
            assertThat(block.sectionPath()).containsExactly("II. METHOD");
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.FORMULA);
            assertThat(block.text()).contains("x = y + 1");
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.TABLE);
            assertThat(block.text()).contains("TABLE I");
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.CAPTION);
            assertThat(block.text()).contains("System architecture");
        });
        assertThat(enriched.blocks().stream()
                .filter(block -> block.text().contains("Author"))
                .allMatch(block -> block.role() == DocumentBlockRole.REFERENCE)).isTrue();

        List<DocumentBlock> evidence = evidencePolicy.selectAllowed(enriched);
        assertThat(evidence).allMatch(block -> switch (block.role()) {
            case ABSTRACT, HEADING, BODY, CAPTION, FORMULA, TABLE -> true;
            default -> false;
        });
        assertThat(evidence).noneMatch(block -> block.text().contains("Journal preprint")
                || block.text().contains("Example work")
                || block.role() == DocumentBlockRole.TITLE
                || block.role() == DocumentBlockRole.AUTHOR);
    }

    @Test
    void isolatesNumberedEquationsAndKeepsEquationMentionsAsProse() {
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                188L, "b".repeat(64), "pdfbox-layout-v1", 0.9,
                Instant.parse("2026-08-11T00:00:00Z"), 1,
                List.of(
                        block(0, 1, 0.08, 0.10, 0.41, 0.01,
                                "Theorem 2. The lower bound is"),
                        block(1, 1, 0.14, 0.115, 0.35, 0.02,
                                "Rk = C(Gamma) - Q(beta). (31)"),
                        block(2, 1, 0.08, 0.140, 0.41, 0.01,
                                "where the terms are defined in Lemmas 4, 5 and 6."),
                        block(3, 1, 0.08, 0.170, 0.41, 0.01,
                                "Based on Lemma 6, we can make E[Y] ≈ E[Gamma] in (34).")));

        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());

        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.text()).isEqualTo("Rk = C(Gamma) - Q(beta). (31)");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.FORMULA);
        });
        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.text()).contains("Based on Lemma 6");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.BODY);
        });
    }

    @Test
    void reclassifiesAHeadingLikeNumberedEquationAsFormula() {
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                206L, "c".repeat(64), "pdfbox-layout-v1", .9,
                Instant.parse("2026-08-12T00:00:00Z"), 1,
                List.of(new DocumentBlock("equation-heading", 1,
                        new NormalizedBoundingBox(.14, .25, .42, .02),
                        DocumentBlockRole.HEADING, 1, List.of(),
                        "H = [H1 H2 … HN]. (12)", null, null, .88)));

        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());

        assertThat(enriched.blocks()).singleElement().satisfies(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.FORMULA);
            assertThat(block.contentMode()).isEqualTo(DocumentBlockContentMode.REGION);
        });
    }

    @Test
    void endsAbstractAtPunctuationlessNumberedIntroduction() {
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                209L, "f".repeat(64), "pdfbox-layout-v1", .9,
                Instant.parse("2026-09-14T00:00:00Z"), 1,
                List.of(
                        block(0, 1, .08, .20, .84, .02,
                                "Abstract This paper presents a system."),
                        block(1, 1, .08, .28, .41, .02, "1 Introduction"),
                        block(2, 1, .08, .30, .41, .02, "The system is motivated by scale.")));

        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());

        assertThat(enriched.blocks()).anySatisfy(block -> {
            assertThat(block.text()).isEqualTo("1 Introduction");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.HEADING);
        });
        assertThat(enriched.blocks().stream()
                .filter(block -> block.role() == DocumentBlockRole.ABSTRACT))
                .allMatch(block -> !block.text().contains("Introduction"));
    }

    @Test
    void separatesExplicitFigureCaptionFromUnconfirmedFigureDiscussion() {
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                207L, "d".repeat(64), "pdfbox-layout-v1", .9,
                Instant.parse("2026-09-13T00:00:00Z"), 1,
                List.of(
                        block(0, 1, .08, .20, .41, .02,
                                "Fig. 9 displays the performance under varying blocklengths."),
                        block(1, 1, .08, .40, .41, .02,
                                "Fig. 9: Ergodic sum-rate versus blocklength.")));

        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());

        assertThat(enriched.blocks()).extracting(DocumentBlock::role)
                .containsExactly(DocumentBlockRole.BODY, DocumentBlockRole.CAPTION);
    }

    @Test
    void acceptsPunctuationlessCaptionOnlyWithAnIndependentVisualAbove() {
        DocumentBlock visual = new DocumentBlock("figure-region", 1,
                new NormalizedBoundingBox(.08, .20, .41, .16),
                DocumentBlockRole.FIGURE, 0, List.of(), "", null, null, .9,
                DocumentBlockContentMode.REGION, MathContentProfile.none(""),
                DocumentLayoutLane.LEFT);
        DocumentBlock caption = new DocumentBlock("caption", 1,
                new NormalizedBoundingBox(.08, .37, .41, .02),
                DocumentBlockRole.BODY, 1, List.of(),
                "Fig. 7 Two-user achievable rate region", null, null, .9,
                DocumentBlockContentMode.TEXT, MathContentProfile.none(""),
                DocumentLayoutLane.LEFT);
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                208L, "e".repeat(64), "pdfbox-layout-v1", .9,
                Instant.parse("2026-09-13T00:00:00Z"), 1,
                List.of(visual, caption));

        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());

        assertThat(enriched.blocks().stream().filter(block -> block.id().equals("caption")))
                .singleElement().satisfies(block ->
                        assertThat(block.role()).isEqualTo(DocumentBlockRole.CAPTION));
    }

    @Test
    void shouldClassifyConfiguredRealPaperWithoutMetadataHints() {
        String samplePath = System.getenv("RA_LAYOUT_SAMPLE");
        assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set RA_LAYOUT_SAMPLE to run the local real-paper semantic check");
        File sample = new File(samplePath);
        assumeTrue(sample.isFile(), "Configured layout sample does not exist");

        PaperLayoutArtifact raw = new PdfBoxPaperLayoutParser().parse(1L, sample);
        PaperLayoutArtifact enriched = enricher.enrich(raw, PaperLayoutHints.empty());
        Map<DocumentBlockRole, Long> counts = enriched.blocks().stream()
                .collect(Collectors.groupingBy(DocumentBlock::role, Collectors.counting()));

        assertThat(counts.getOrDefault(DocumentBlockRole.TITLE, 0L)).isGreaterThanOrEqualTo(1);
        assertThat(counts.getOrDefault(DocumentBlockRole.AUTHOR, 0L)).isGreaterThanOrEqualTo(1);
        assertThat(counts.getOrDefault(DocumentBlockRole.ABSTRACT, 0L)).isGreaterThanOrEqualTo(1);
        assertThat(counts.getOrDefault(DocumentBlockRole.HEADING, 0L)).isGreaterThan(5);
        assertThat(counts.getOrDefault(DocumentBlockRole.REFERENCE, 0L)).isGreaterThan(10);
        assertThat(enriched.blocks().stream()
                .filter(block -> block.role() == DocumentBlockRole.ABSTRACT))
                .noneMatch(block -> block.text().matches("(?is).*this work was supported.*"));
        assertThat(evidencePolicy.selectAllowed(enriched))
                .noneMatch(block -> block.role() == DocumentBlockRole.HEADER
                        || block.role() == DocumentBlockRole.FOOTER
                        || block.role() == DocumentBlockRole.MARGIN_METADATA
                        || block.role() == DocumentBlockRole.REFERENCE);
        System.out.printf("SEMANTIC_SAMPLE raw=%d semantic=%d evidence=%d roles=%s%n",
                raw.blocks().size(), enriched.blocks().size(),
                evidencePolicy.selectAllowed(enriched).size(), counts);
    }

    private DocumentBlock block(int order,
                                int page,
                                double x,
                                double y,
                                double width,
                                double height,
                                String text) {
        return new DocumentBlock(
                "p%d-b%04d".formatted(page, order),
                page,
                new NormalizedBoundingBox(x, y, width, height),
                DocumentBlockRole.BODY,
                order,
                List.of(),
                text,
                null,
                null,
                0.9
        );
    }
}
