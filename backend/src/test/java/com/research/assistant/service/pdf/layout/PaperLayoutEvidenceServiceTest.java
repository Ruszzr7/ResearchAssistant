package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.pdf.math.InlineMathTranscription;
import com.research.assistant.service.pdf.math.InlineMathTranscriptionService;
import com.research.assistant.service.pdf.math.ClientSelectionMathTranscriber;
import com.research.assistant.service.pdf.math.LocalUnicodeMathTranscriptionProvider;
import com.research.assistant.service.pdf.math.MathTranscriptionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                .containsExactly("body-1", "body-2", "heading-1")
                .doesNotContain("header-1", "reference-1");
        assertThat(result.evidence()).allMatch(item -> policy.isAllowed(
                artifact.blocks().stream().filter(block -> block.id().equals(item.blockId())).findFirst().orElseThrow()));
        assertThat(result.evidence().stream().filter(LayoutEvidence::selected))
                .extracting(LayoutEvidence::blockId)
                .containsExactly("body-2");
        assertThat(result.evidence()).allMatch(item -> item.evidenceId().startsWith("lay_"));
        LayoutEvidence selected = result.evidence().stream().filter(LayoutEvidence::selected)
                .findFirst().orElseThrow();
        assertThat(selected.text()).isEqualTo("selected paragraph evidence");
        assertThat(selected.selectedRanges()).hasSize(1);
    }

    @Test
    void enrichesOnlyTheSelectedMathFragmentsForTheModel() {
        String text = "where p_c ∈ ℂ, satisfying μ_k ≥ 0 and Σ μ_k = 1";
        DocumentBlock mathBlock = new DocumentBlock("math", 1,
                new NormalizedBoundingBox(0.08, 0.2, 0.82, 0.15), DocumentBlockRole.BODY,
                0, List.of("SYSTEM MODEL"), text, null, null, 0.9);
        mathBlock = new PaperMathContentEnricher().enrich(mathBlock);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(9L, "m".repeat(64),
                "parser+inline-math-v1", 0.9, Instant.now(), 1, List.of(mathBlock));
        InlineMathTranscriptionService transcriptionService = mock(InlineMathTranscriptionService.class);
        when(transcriptionService.transcribe(any(), any(), any())).thenAnswer(invocation -> {
            InlineMathFragment fragment = invocation.getArgument(2);
            return new InlineMathTranscription("math", fragment.start(), fragment.end(),
                    fragment.sourceText(), "\\mu_k \\ge 0", MathTranscriptionStatus.APPROXIMATE,
                    0.74, "local", "回原页核对", false);
        });
        PaperLayoutEvidenceService mathService = new PaperLayoutEvidenceService(
                policy, resolver, null, transcriptionService);
        SelectionAnchor anchor = resolver.resolve(artifact, 1, List.of(mathBlock.bbox()), text, null);

        LocalEvidenceResult result = mathService.retrieve(artifact, anchor, "解释公式", 3);

        LayoutEvidence selected = result.evidence().get(0);
        assertThat(selected.selected()).isTrue();
        assertThat(selected.mathTranscriptions()).isNotEmpty();
        assertThat(selected.mathTranscriptions())
                .allMatch(item -> item.status() == MathTranscriptionStatus.APPROXIMATE);
    }

    @Test
    void addsVerifiedPdfiumMathToTheSelectedEvidenceOnlyOnce() {
        PaperLayoutArtifact artifact = artifact();
        ClientTextAnchor client = new ClientTextAnchor(2, 1, "fingerprint", 1, List.of(),
                "PDFIUM", 100, 130, List.of(new ClientContentSegment(
                ClientContentSegmentType.INLINE_MATH, 110, 118, "μ_k ≥ 0",
                List.of("CMMI10"), new NormalizedBoundingBox(0.12, 0.35, 0.08, 0.02))));
        SelectionAnchor anchor = resolver.resolve(artifact, 1,
                List.of(new NormalizedBoundingBox(0.10, 0.35, 0.30, 0.03)),
                "selected paragraph evidence with μ_k ≥ 0", null, client);
        PaperLayoutEvidenceService pdfiumService = new PaperLayoutEvidenceService(
                policy, resolver, null, null,
                new ClientSelectionMathTranscriber(List.of(new LocalUnicodeMathTranscriptionProvider())));

        LocalEvidenceResult result = pdfiumService.retrieve(artifact, anchor, "解释公式", 5);

        List<InlineMathTranscription> all = result.evidence().stream()
                .flatMap(item -> item.mathTranscriptions().stream()).toList();
        assertThat(all).singleElement().satisfies(item -> {
            assertThat(item.sourceText()).isEqualTo("μ_k ≥ 0");
            assertThat(item.status()).isEqualTo(MathTranscriptionStatus.APPROXIMATE);
            assertThat(item.start()).isEqualTo(110);
        });
    }

    @Test
    void returnsAtMostOneAllowedBlockOnEachSideOfTheSelection() {
        PaperLayoutArtifact artifact = artifact();
        SelectionAnchor anchor = resolver.resolve(
                artifact,
                1,
                List.of(new NormalizedBoundingBox(0.10, 0.62, 0.30, 0.02)),
                "next section channel model",
                null);

        LocalEvidenceResult result = service.retrieve(artifact, anchor, "channel", 8);

        assertThat(result.evidence()).extracting(LayoutEvidence::blockId)
                .containsExactly("heading-1", "body-3");
        assertThat(result.evidence().stream().filter(LayoutEvidence::selected))
                .extracting(LayoutEvidence::blockId).containsExactly("body-3");
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
