package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionAnchorResolverTest {

    private final PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
    private final SelectionAnchorResolver resolver = new SelectionAnchorResolver(policy);

    @Test
    void mapsReliableBodySelectionToVersionedTextAnchor() {
        PaperLayoutArtifact artifact = artifact();

        SelectionAnchor anchor = resolver.resolve(
                artifact,
                1,
                List.of(new NormalizedBoundingBox(0.12, 0.24, 0.20, 0.03)),
                "rate-splitting improves the achievable rate",
                null);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.TEXT);
        assertThat(anchor.blockIds()).containsExactly("body-1");
        assertThat(anchor.documentHash()).isEqualTo("a".repeat(64));
        assertThat(anchor.parserVersion()).isEqualTo("parser+semantic");
        assertThat(anchor.confidence()).isGreaterThan(0.8);
        assertThat(anchor.tokenRange()).isNotNull();
        assertThat(anchor.mappingStatus()).isEqualTo(SelectionMappingStatus.EXACT);
        assertThat(anchor.contentType()).isEqualTo(SelectionContentType.PLAIN_TEXT);
        assertThat(anchor.evidenceUse()).isEqualTo(SelectionEvidenceUse.CLAIM_EVIDENCE);
        assertThat(anchor.blockRanges()).containsExactly(new SelectionBlockRange("body-1", 13, 56));
    }

    @Test
    void degradesHeaderSelectionToRegionEvenWhenGeometryMatches() {
        SelectionAnchor anchor = resolver.resolve(
                artifact(),
                1,
                List.of(new NormalizedBoundingBox(0.15, 0.02, 0.30, 0.02)),
                "IEEE Transactions on Communications",
                null);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.REGION);
        assertThat(anchor.blockIds()).containsExactly("header-1");
        assertThat(anchor.mappingStatus()).isEqualTo(SelectionMappingStatus.EXACT);
        assertThat(anchor.evidenceUse()).isEqualTo(SelectionEvidenceUse.VISUAL_ONLY);
    }

    @Test
    void keepsExactReferenceMappingSeparateFromEvidenceEligibility() {
        SelectionAnchor anchor = resolver.resolve(
                artifact(),
                2,
                List.of(new NormalizedBoundingBox(0.08, 0.20, 0.40, 0.20)),
                "[1] A reference that must not become evidence",
                null);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.REGION);
        assertThat(anchor.mappingStatus()).isEqualTo(SelectionMappingStatus.EXACT);
        assertThat(anchor.contentType()).isEqualTo(SelectionContentType.REFERENCE);
        assertThat(anchor.evidenceUse()).isEqualTo(SelectionEvidenceUse.METADATA_ONLY);
    }

    @Test
    void recoversOffsetsAcrossUnicodeLigaturesAndSoftHyphens() {
        PaperLayoutArtifact source = new PaperLayoutArtifact(
                9L, "b".repeat(64), "parser", 0.9, Instant.now(), 1,
                List.of(block("unicode", 1, 0, DocumentBlockRole.BODY,
                        new NormalizedBoundingBox(0.1, 0.2, 0.7, 0.1),
                        "An efﬁcient rate-\u00ADsplitting method")));

        SelectionAnchor anchor = resolver.resolve(source, 1,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.7, 0.1)),
                "efficient rate splitting", null);

        assertThat(anchor.mappingStatus()).isEqualTo(SelectionMappingStatus.EXACT);
        assertThat(anchor.blockRanges()).hasSize(1);
        assertThat(anchor.blockRanges().get(0).blockId()).isEqualTo("unicode");
        assertThat(source.blocks().get(0).text().substring(
                anchor.blockRanges().get(0).start(), anchor.blockRanges().get(0).end()))
                .isEqualTo("efﬁcient rate-\u00ADsplitting");
    }

    @Test
    void keepsFormulaKindOnlyWhenAFormulaBlockIsActuallyHit() {
        SelectionAnchor anchor = resolver.resolve(
                artifact(),
                1,
                List.of(new NormalizedBoundingBox(0.12, 0.67, 0.30, 0.05)),
                "R = log(1 + SINR)",
                SelectionAnchorKind.FORMULA);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.FORMULA);
        assertThat(anchor.blockIds()).containsExactly("formula-1");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RA_LAYOUT_SAMPLE", matches = ".+")
    void mapsABodyParagraphInTheRealPdfSample() {
        File sample = new File(System.getenv("RA_LAYOUT_SAMPLE"));
        PaperLayoutArtifact raw = new PdfBoxPaperLayoutParser().parse(175L, sample);
        PaperLayoutArtifact enriched = new PaperLayoutSemanticEnricher().enrich(
                raw, new PaperLayoutHints("", "", ""));
        DocumentBlock body = enriched.blocks().stream()
                .filter(block -> block.role() == DocumentBlockRole.BODY)
                .filter(block -> block.text().length() >= 30)
                .findFirst()
                .orElseThrow();
        String selectedText = body.text().substring(0, Math.min(120, body.text().length()));

        SelectionAnchor anchor = resolver.resolve(
                enriched, body.page(), List.of(body.bbox()), selectedText, null);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.TEXT);
        assertThat(anchor.blockIds()).contains(body.id());
    }

    private PaperLayoutArtifact artifact() {
        return new PaperLayoutArtifact(
                9L,
                "a".repeat(64),
                "parser+semantic",
                0.9,
                Instant.parse("2026-07-16T00:00:00Z"),
                2,
                List.of(
                        block("header-1", 1, 0, DocumentBlockRole.HEADER,
                                new NormalizedBoundingBox(0.10, 0.01, 0.80, 0.04),
                                "IEEE Transactions on Communications"),
                        block("body-1", 1, 1, DocumentBlockRole.BODY,
                                new NormalizedBoundingBox(0.08, 0.20, 0.40, 0.22),
                                "The proposed rate-splitting improves the achievable rate in short packets."),
                        block("body-2", 1, 2, DocumentBlockRole.BODY,
                                new NormalizedBoundingBox(0.08, 0.44, 0.40, 0.16),
                                "The decoder first recovers the common stream and then private streams."),
                        block("formula-1", 1, 3, DocumentBlockRole.FORMULA,
                                new NormalizedBoundingBox(0.08, 0.64, 0.40, 0.10),
                                "R = log(1 + SINR)"),
                        block("reference-1", 2, 4, DocumentBlockRole.REFERENCE,
                                new NormalizedBoundingBox(0.08, 0.20, 0.40, 0.20),
                                "[1] A reference that must not become evidence")));
    }

    private DocumentBlock block(String id,
                                int page,
                                int order,
                                DocumentBlockRole role,
                                NormalizedBoundingBox bbox,
                                String text) {
        return new DocumentBlock(id, page, bbox, role, order,
                List.of("I. INTRODUCTION"), text,
                role == DocumentBlockRole.FORMULA ? text : null,
                null, 0.9);
    }
}
