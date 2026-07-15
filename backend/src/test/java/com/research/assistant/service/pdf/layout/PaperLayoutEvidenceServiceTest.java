package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaperLayoutEvidenceServiceTest {

    private final PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
    private final SelectionAnchorResolver resolver = new SelectionAnchorResolver(policy);
    private final PaperLayoutEvidenceService service = new PaperLayoutEvidenceService(policy, resolver);

    @Test
    void returnsSelectedBlockAndBoundedNeighboursButNeverFurnitureOrReferences() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor anchor = resolver.resolve(
                artifact,
                1,
                List.of(new NormalizedBoundingBox(0.10, 0.35, 0.30, 0.03)),
                "selected paragraph evidence",
                null);

        LocalEvidenceResult result = service.retrieve(artifact, anchor, "decoder rate", 5);

        assertThat(result.regionFallback()).isFalse();
        assertThat(result.evidence()).extracting(LayoutEvidence::blockId)
                .contains("body-2")
                .doesNotContain("header-1", "reference-1");
        assertThat(result.evidence()).allMatch(item -> policy.isAllowed(
                artifact.blocks().stream().filter(block -> block.id().equals(item.blockId())).findFirst().orElseThrow()));
        assertThat(result.evidence().stream().filter(LayoutEvidence::selected))
                .extracting(LayoutEvidence::blockId)
                .containsExactly("body-2");
        assertThat(result.evidence()).allMatch(item -> item.evidenceId().startsWith("lay_"));
    }

    @Test
    void returnsNoTextEvidenceForAHeaderOnlyRegion() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor anchor = resolver.resolve(
                artifact,
                1,
                List.of(new NormalizedBoundingBox(0.10, 0.01, 0.50, 0.03)),
                "repeated page header",
                null);

        LocalEvidenceResult result = service.retrieve(artifact, anchor, "header", 5);

        assertThat(result.regionFallback()).isTrue();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void rejectsAnchorFromAReplacedPdf() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor stale = new SelectionAnchor(
                9L, 1, List.of(new NormalizedBoundingBox(0.1, 0.3, 0.2, 0.03)),
                "text", List.of("body-1"), null, SelectionAnchorKind.TEXT,
                0.9, "b".repeat(64), artifact.parserVersion());

        assertThatThrownBy(() -> service.retrieve(artifact, stale, "text", 5))
                .isInstanceOf(StaleLayoutArtifactException.class);
    }

    @Test
    void evidenceIdentityIsStableAcrossRepeatedRetrievals() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor anchor = resolver.resolve(
                artifact, 1, List.of(new NormalizedBoundingBox(0.1, 0.21, 0.2, 0.03)),
                "first body paragraph", null);

        List<String> first = service.retrieve(artifact, anchor, "body", 4).evidence().stream()
                .map(LayoutEvidence::evidenceId).toList();
        List<String> second = service.retrieve(artifact, anchor, "body", 4).evidence().stream()
                .map(LayoutEvidence::evidenceId).toList();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void ignoresForgedClientBlockIdsAndResolvesGeometryAgain() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor forged = new SelectionAnchor(
                9L,
                1,
                List.of(new NormalizedBoundingBox(0.10, 0.21, 0.20, 0.03)),
                "first body paragraph",
                List.of("body-3", "reference-1"),
                null,
                SelectionAnchorKind.TEXT,
                0.99,
                artifact.documentHash(),
                artifact.parserVersion());

        LocalEvidenceResult result = service.retrieve(artifact, forged, "body", 4);

        assertThat(result.anchor().blockIds()).containsExactly("body-1");
        assertThat(result.evidence().stream().filter(LayoutEvidence::selected))
                .extracting(LayoutEvidence::blockId)
                .containsExactly("body-1");
    }

    private PaperLayoutArtifact artifact() {
        return new PaperLayoutArtifact(
                9L, "a".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-16T00:00:00Z"), 2,
                List.of(
                        block("header-1", 1, 0, DocumentBlockRole.HEADER, 0.01, "repeated page header"),
                        block("body-1", 1, 1, DocumentBlockRole.BODY, 0.20, "The first body paragraph introduces rate splitting."),
                        block("body-2", 1, 2, DocumentBlockRole.BODY, 0.34, "This selected paragraph evidence describes the decoder rate."),
                        block("heading-1", 1, 3, DocumentBlockRole.HEADING, 0.48, "II. SYSTEM MODEL"),
                        block("body-3", 1, 4, DocumentBlockRole.BODY, 0.56, "The next section defines the channel model."),
                        block("reference-1", 2, 5, DocumentBlockRole.REFERENCE, 0.20, "[1] forbidden reference")));
    }

    private DocumentBlock block(String id,
                                int page,
                                int order,
                                DocumentBlockRole role,
                                double y,
                                String text) {
        List<String> section = order < 3 ? List.of("I. INTRODUCTION") : List.of("II. SYSTEM MODEL");
        return new DocumentBlock(id, page, new NormalizedBoundingBox(0.08, y, 0.42, 0.10),
                role, order, section, text, null, null, 0.9);
    }
}
