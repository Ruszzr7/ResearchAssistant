package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PaperLayoutQualityAssessorTest {

    private final PaperLayoutQualityAssessor assessor = new PaperLayoutQualityAssessor();

    @Test
    void shouldKeepDenseCleanPageOnFastPath() {
        List<DocumentBlock> blocks = IntStream.range(0, 12)
                .mapToObj(index -> block(index, "layout aware evidence ".repeat(8), 0.88))
                .toList();
        PaperLayoutArtifact artifact = artifact(0.9, blocks);

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.score()).isGreaterThan(0.8);
        assertThat(report.fallbackRecommended()).isFalse();
        assertThat(report.issues()).doesNotContain(LayoutQualityIssue.SPARSE_TEXT_COVERAGE);
    }

    @Test
    void shouldRequestFallbackForSparseOrCorruptedExtraction() {
        PaperLayoutArtifact artifact = artifact(0.45, List.of(
                block(0, "x � �", 0.4)));

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.fallbackRecommended()).isTrue();
        assertThat(report.score()).isLessThan(PaperLayoutQualityAssessor.FALLBACK_THRESHOLD);
        assertThat(report.issues()).contains(
                LayoutQualityIssue.SPARSE_TEXT_COVERAGE,
                LayoutQualityIssue.CORRUPTED_TEXT,
                LayoutQualityIssue.LOW_BLOCK_CONFIDENCE);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RA_LAYOUT_SAMPLE", matches = ".+")
    void shouldKeepConfiguredRealPaperOnFastPath() {
        PaperLayoutArtifact raw = new PdfBoxPaperLayoutParser().parse(
                175L, new File(System.getenv("RA_LAYOUT_SAMPLE")));

        LayoutQualityReport report = assessor.assess(raw);

        assertThat(report.fallbackRecommended()).isFalse();
        assertThat(report.score()).isGreaterThan(0.8);
        System.out.printf("LAYOUT_QUALITY score=%.3f chars=%d geometry=%.3f clean=%.3f issues=%s%n",
                report.score(), report.textCharacters(), report.validGeometryRatio(),
                report.cleanTextRatio(), report.issues());
    }

    private PaperLayoutArtifact artifact(double confidence, List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(1L, "a".repeat(64), "parser", confidence,
                Instant.EPOCH, 1, blocks);
    }

    private DocumentBlock block(int order, String text, double confidence) {
        return new DocumentBlock("b" + order, 1,
                new NormalizedBoundingBox(0.08, 0.1 + order * 0.04, 0.4, 0.02),
                DocumentBlockRole.BODY, order, List.of(), text, null, null, confidence);
    }
}
