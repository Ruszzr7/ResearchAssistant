package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
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
    void shouldDetectDoubleColumnInversionEvenWhenReadingOrdersAreUnique() {
        PaperLayoutArtifact artifact = artifact(0.92, List.of(
                blockAt(0, "left top text", 0.08, 0.10, 0.40, DocumentLayoutLane.LEFT),
                blockAt(1, "right top text", 0.56, 0.10, 0.36, DocumentLayoutLane.RIGHT),
                blockAt(2, "right middle text", 0.56, 0.16, 0.36, DocumentLayoutLane.RIGHT),
                blockAt(3, "left middle text", 0.08, 0.16, 0.40, DocumentLayoutLane.LEFT),
                blockAt(4, "right bottom text", 0.56, 0.22, 0.36, DocumentLayoutLane.RIGHT),
                blockAt(5, "left bottom text", 0.08, 0.22, 0.40, DocumentLayoutLane.LEFT)));

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.readingOrderScore()).isLessThan(1.0);
        assertThat(report.issues()).contains(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        assertThat(report.fallbackRecommended()).isTrue();
    }

    @Test
    void shouldNotDiluteOneBadDoubleColumnPageAcrossCleanPages() {
        List<DocumentBlock> blocks = new ArrayList<>();
        int order = 0;
        blocks.add(blockAt(1, order++, "left top text", .08, .10, .40, DocumentLayoutLane.LEFT));
        blocks.add(blockAt(1, order++, "right top text", .56, .10, .36, DocumentLayoutLane.RIGHT));
        blocks.add(blockAt(1, order++, "right middle text", .56, .16, .36, DocumentLayoutLane.RIGHT));
        blocks.add(blockAt(1, order++, "left middle text", .08, .16, .40, DocumentLayoutLane.LEFT));
        blocks.add(blockAt(1, order++, "left bottom text", .08, .22, .40, DocumentLayoutLane.LEFT));
        blocks.add(blockAt(1, order++, "right bottom text", .56, .22, .36, DocumentLayoutLane.RIGHT));
        for (int page = 2; page <= 13; page++) {
            blocks.add(blockAt(page, order++, "left top clean text", .08, .10, .40, DocumentLayoutLane.LEFT));
            blocks.add(blockAt(page, order++, "left middle clean text", .08, .16, .40, DocumentLayoutLane.LEFT));
            blocks.add(blockAt(page, order++, "left bottom clean text", .08, .22, .40, DocumentLayoutLane.LEFT));
            blocks.add(blockAt(page, order++, "right top clean text", .56, .10, .36, DocumentLayoutLane.RIGHT));
            blocks.add(blockAt(page, order++, "right middle clean text", .56, .16, .36, DocumentLayoutLane.RIGHT));
            blocks.add(blockAt(page, order++, "right bottom clean text", .56, .22, .36, DocumentLayoutLane.RIGHT));
        }

        LayoutQualityReport report = assessor.assess(artifact(.92, 13, blocks));

        assertThat(report.readingOrderScore()).isLessThan(.92);
        assertThat(report.issues()).contains(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        assertThat(report.fallbackRecommended()).isTrue();
    }

    @Test
    void shouldKeepCorrectDoubleColumnOrderOnFastPath() {
        PaperLayoutArtifact artifact = artifact(0.92, List.of(
                blockAt(0, "left top text", 0.08, 0.10, 0.40, DocumentLayoutLane.LEFT),
                blockAt(1, "left middle text", 0.08, 0.16, 0.40, DocumentLayoutLane.LEFT),
                blockAt(2, "left bottom text", 0.08, 0.22, 0.40, DocumentLayoutLane.LEFT),
                blockAt(3, "right top text", 0.56, 0.10, 0.36, DocumentLayoutLane.RIGHT),
                blockAt(4, "right middle text", 0.56, 0.16, 0.36, DocumentLayoutLane.RIGHT),
                blockAt(5, "right bottom text", 0.56, 0.22, 0.36, DocumentLayoutLane.RIGHT)));

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.readingOrderScore()).isEqualTo(1.0);
        assertThat(report.issues()).doesNotContain(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        assertThat(report.fallbackRecommended()).isFalse();
    }

    @Test
    void shouldIgnoreVerticalNoiseInsideOneLane() {
        PaperLayoutArtifact artifact = artifact(0.92, List.of(
                blockAt(0, "left paragraph one", .08, .10, .40, DocumentLayoutLane.LEFT),
                blockAt(1, "x", .18, .18, .02, DocumentLayoutLane.LEFT),
                blockAt(2, "left paragraph two", .08, .14, .40, DocumentLayoutLane.LEFT),
                blockAt(3, "y", .20, .12, .02, DocumentLayoutLane.LEFT),
                blockAt(4, "left paragraph three", .08, .22, .40, DocumentLayoutLane.LEFT),
                blockAt(5, "right paragraph one", .56, .10, .36, DocumentLayoutLane.RIGHT),
                blockAt(6, "right paragraph two", .56, .16, .36, DocumentLayoutLane.RIGHT),
                blockAt(7, "right paragraph three", .56, .22, .36, DocumentLayoutLane.RIGHT)));

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.readingOrderScore()).isEqualTo(1.0);
        assertThat(report.issues()).doesNotContain(LayoutQualityIssue.UNSTABLE_READING_ORDER);
    }

    @Test
    void shouldResetColumnPhaseAfterFullWidthObject() {
        PaperLayoutArtifact artifact = artifact(0.92, List.of(
                blockAt(0, "left upper one", .08, .10, .40, DocumentLayoutLane.LEFT),
                blockAt(1, "left upper two", .08, .13, .40, DocumentLayoutLane.LEFT),
                blockAt(2, "left upper three", .08, .16, .40, DocumentLayoutLane.LEFT),
                blockAt(3, "right upper one", .56, .10, .36, DocumentLayoutLane.RIGHT),
                blockAt(4, "right upper two", .56, .13, .36, DocumentLayoutLane.RIGHT),
                blockAt(5, "right upper three", .56, .16, .36, DocumentLayoutLane.RIGHT),
                blockAt(6, "full width algorithm object", .08, .25, .84, DocumentLayoutLane.FULL),
                blockAt(7, "left lower one", .08, .35, .40, DocumentLayoutLane.LEFT),
                blockAt(8, "left lower two", .08, .38, .40, DocumentLayoutLane.LEFT),
                blockAt(9, "left lower three", .08, .41, .40, DocumentLayoutLane.LEFT),
                blockAt(10, "right lower one", .56, .35, .36, DocumentLayoutLane.RIGHT),
                blockAt(11, "right lower two", .56, .38, .36, DocumentLayoutLane.RIGHT),
                blockAt(12, "right lower three", .56, .41, .36, DocumentLayoutLane.RIGHT)));

        LayoutQualityReport report = assessor.assess(artifact);

        assertThat(report.readingOrderScore()).isEqualTo(1.0);
        assertThat(report.issues()).doesNotContain(LayoutQualityIssue.UNSTABLE_READING_ORDER);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RA_LAYOUT_SAMPLE", matches = ".+")
    void shouldAssessConfiguredRealPaperConsistently() {
        PaperLayoutArtifact raw = new PdfBoxPaperLayoutParser().parse(
                175L, new File(System.getenv("RA_LAYOUT_SAMPLE")));

        LayoutQualityReport report = assessor.assess(raw);

        System.out.printf("LAYOUT_QUALITY score=%.3f order=%.3f chars=%d geometry=%.3f clean=%.3f issues=%s%n",
                report.score(), report.readingOrderScore(), report.textCharacters(), report.validGeometryRatio(),
                report.cleanTextRatio(), report.issues());
        assertThat(report.score()).isGreaterThan(0.8);
        if (report.fallbackRecommended()) {
            assertThat(report.issues()).contains(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        }
    }

    private PaperLayoutArtifact artifact(double confidence, List<DocumentBlock> blocks) {
        return artifact(confidence, 1, blocks);
    }

    private PaperLayoutArtifact artifact(double confidence, int pageCount, List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(1L, "a".repeat(64), "parser", confidence,
                Instant.EPOCH, pageCount, blocks);
    }

    private DocumentBlock block(int order, String text, double confidence) {
        return blockAt(order, text, 0.08, 0.1 + order * 0.04, 0.4, confidence);
    }

    private DocumentBlock blockAt(int order, String text, double x, double y, double width) {
        return blockAt(order, text, x, y, width, 0.88, DocumentLayoutLane.SINGLE);
    }

    private DocumentBlock blockAt(int order, String text, double x, double y, double width,
                                  DocumentLayoutLane lane) {
        return blockAt(order, text, x, y, width, 0.88, lane);
    }

    private DocumentBlock blockAt(int order,
                                  String text,
                                  double x,
                                  double y,
                                  double width,
                                  double confidence) {
        return blockAt(order, text, x, y, width, confidence, DocumentLayoutLane.SINGLE);
    }

    private DocumentBlock blockAt(int order, String text, double x, double y, double width,
                                  double confidence, DocumentLayoutLane lane) {
        return blockAt(1, order, text, x, y, width, confidence, lane);
    }

    private DocumentBlock blockAt(int page, int order, String text, double x, double y, double width,
                                  DocumentLayoutLane lane) {
        return blockAt(page, order, text, x, y, width, .88, lane);
    }

    private DocumentBlock blockAt(int page, int order, String text, double x, double y, double width,
                                  double confidence, DocumentLayoutLane lane) {
        return new DocumentBlock("p" + page + "-b" + order, page,
                new NormalizedBoundingBox(x, y, width, 0.02),
                DocumentBlockRole.BODY, order, List.of(), text, null, null, confidence,
                null, null, lane);
    }
}
