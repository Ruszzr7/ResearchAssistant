package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdaptivePaperLayoutParserTest {

    private PdfBoxPaperLayoutParser primary;
    private ExternalLayoutParserAdapter fallback;
    private AdaptivePaperLayoutParser parser;
    private final File file = new File("paper.pdf");
    private final String hash = "a".repeat(64);

    @BeforeEach
    void setUp() {
        primary = mock(PdfBoxPaperLayoutParser.class);
        fallback = mock(ExternalLayoutParserAdapter.class);
        when(primary.parserVersion()).thenReturn("pdfbox-layout-v1");
        when(fallback.policyFingerprint()).thenReturn("mineru-test");
        parser = new AdaptivePaperLayoutParser(primary, fallback, new PaperLayoutQualityAssessor());
    }

    @Test
    void shouldNotRunFallbackForHighQualityPrimaryArtifact() {
        when(primary.parse(1L, file, hash)).thenReturn(highQuality("primary"));

        PaperLayoutArtifact result = parser.parse(1L, file, hash);

        verify(fallback, never()).parse(1L, file, hash);
        assertThat(result.provenance().fallbackEligible()).isFalse();
        assertThat(result.provenance().selectedParser()).isEqualTo("pdfbox-layout-v1");
    }

    @Test
    void shouldAcceptConfiguredFallbackOnlyWhenQualityImproves() {
        when(primary.parse(1L, file, hash)).thenReturn(lowQuality());
        when(fallback.enabled()).thenReturn(true);
        PaperLayoutArtifact external = highQuality("mineru-external-layout-v1");
        when(fallback.parse(1L, file, hash))
                .thenReturn(ExternalLayoutParseResult.success(external, "mineru"));

        PaperLayoutArtifact result = parser.parse(1L, file, hash);

        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .allMatch(text -> text.startsWith("mineru-external-layout-v1"));
        assertThat(result.provenance().fallbackAttempted()).isTrue();
        assertThat(result.provenance().fallbackAccepted()).isTrue();
        assertThat(result.provenance().fallbackQuality())
                .isGreaterThan(result.provenance().primaryQuality());
    }

    @Test
    void shouldRunFallbackForGeometricOrderAnomaly() {
        when(primary.parse(1L, file, hash)).thenReturn(geometricallyUnstable());
        when(fallback.enabled()).thenReturn(true);
        PaperLayoutArtifact external = geometricallyCorrect("mineru-external-layout-v1");
        when(fallback.parse(1L, file, hash))
                .thenReturn(ExternalLayoutParseResult.success(external, "mineru"));

        PaperLayoutArtifact result = parser.parse(1L, file, hash);

        verify(fallback).parse(1L, file, hash);
        assertThat(result.provenance().fallbackEligible()).isTrue();
        assertThat(result.provenance().fallbackAttempted()).isTrue();
        assertThat(result.provenance().fallbackAccepted()).isTrue();
        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .allMatch(text -> text.startsWith("mineru-external-layout-v1"));
    }

    @Test
    void shouldKeepPrimaryAndPersistSafeFailureCodeWhenFallbackFails() {
        when(primary.parse(1L, file, hash)).thenReturn(lowQuality());
        when(fallback.enabled()).thenReturn(true);
        when(fallback.parse(1L, file, hash))
                .thenReturn(ExternalLayoutParseResult.failure("mineru", "FALLBACK_TIMEOUT"));

        PaperLayoutArtifact result = parser.parse(1L, file, hash);

        assertThat(result.provenance().fallbackAccepted()).isFalse();
        assertThat(result.provenance().fallbackFailureCode()).isEqualTo("FALLBACK_TIMEOUT");
        assertThat(result.blocks()).extracting(DocumentBlock::text).containsExactly("x �");
    }

    private PaperLayoutArtifact lowQuality() {
        return new PaperLayoutArtifact(1L, hash, "pdfbox-layout-v1", 0.3,
                Instant.EPOCH, 1, List.of(block(0, "x �", 0.3)));
    }

    private PaperLayoutArtifact highQuality(String prefix) {
        List<DocumentBlock> blocks = IntStream.range(0, 12)
                .mapToObj(index -> block(index, prefix + " evidence ".repeat(10), 0.9))
                .toList();
        return new PaperLayoutArtifact(1L, hash, prefix, 0.92,
                Instant.EPOCH, 1, blocks);
    }

    private PaperLayoutArtifact geometricallyUnstable() {
        String text = "primary evidence ".repeat(12);
        return new PaperLayoutArtifact(1L, hash, "pdfbox-layout-v4", 0.92,
                Instant.EPOCH, 1, List.of(
                blockAt(0, text + "left top", 0.08, 0.10, 0.40, 0.9),
                blockAt(1, text + "right top", 0.56, 0.10, 0.36, 0.9),
                blockAt(2, text + "right middle", 0.56, 0.16, 0.36, 0.9),
                blockAt(3, text + "left middle", 0.08, 0.16, 0.40, 0.9),
                blockAt(4, text + "right bottom", 0.56, 0.22, 0.36, 0.9),
                blockAt(5, text + "left bottom", 0.08, 0.22, 0.40, 0.9)));
    }

    private PaperLayoutArtifact geometricallyCorrect(String prefix) {
        String text = prefix + " evidence ".repeat(12);
        return new PaperLayoutArtifact(1L, hash, prefix, 0.92,
                Instant.EPOCH, 1, List.of(
                blockAt(0, text + "left top", 0.08, 0.10, 0.40, 0.9),
                blockAt(1, text + "left middle", 0.08, 0.16, 0.40, 0.9),
                blockAt(2, text + "left bottom", 0.08, 0.22, 0.40, 0.9),
                blockAt(3, text + "right top", 0.56, 0.10, 0.36, 0.9),
                blockAt(4, text + "right middle", 0.56, 0.16, 0.36, 0.9),
                blockAt(5, text + "right bottom", 0.56, 0.22, 0.36, 0.9)));
    }

    private DocumentBlock block(int order, String text, double confidence) {
        return blockAt(order, text, 0.08, 0.08 + order * 0.045, 0.42, confidence);
    }

    private DocumentBlock blockAt(int order,
                                  String text,
                                  double x,
                                  double y,
                                  double width,
                                  double confidence) {
        return new DocumentBlock("b" + order, 1,
                new NormalizedBoundingBox(x, y, width, 0.025),
                DocumentBlockRole.BODY, order, List.of(), text, null, null, confidence,
                null, null, x >= 0.48 ? DocumentLayoutLane.RIGHT : DocumentLayoutLane.LEFT);
    }
}
