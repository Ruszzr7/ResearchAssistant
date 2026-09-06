package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptivePaperLayoutParserTest {

    @Test
    void shouldKeepLocalArtifactAndExposeQualitySignals() {
        PdfBoxPaperLayoutParser primary = mock(PdfBoxPaperLayoutParser.class);
        when(primary.parserVersion()).thenReturn("pdfbox-layout-v1");
        String hash = "a".repeat(64);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(1L, hash, "pdfbox-layout-v1", 0.3,
                Instant.EPOCH, 1, List.of(new DocumentBlock("b1", 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.4, 0.03), DocumentBlockRole.BODY,
                0, List.of(), "x �", null, null, 0.3, null, null, DocumentLayoutLane.LEFT)));
        when(primary.parse(1L, new File("paper.pdf"), hash)).thenReturn(artifact);

        PaperLayoutArtifact result = new AdaptivePaperLayoutParser(primary, new PaperLayoutQualityAssessor())
                .parse(1L, new File("paper.pdf"), hash);

        assertThat(result.blocks()).extracting(DocumentBlock::text).containsExactly("x �");
        assertThat(result.provenance().fallbackEligible()).isTrue();
        assertThat(result.provenance().fallbackAttempted()).isFalse();
        assertThat(result.provenance().fallbackAccepted()).isFalse();
        assertThat(result.provenance().selectedParser()).isEqualTo("pdfbox-layout-v1");
    }
}
