package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegionEvidenceModeTest {

    private final PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
    private final SelectionAnchorResolver resolver = new SelectionAnchorResolver(policy);
    private final PaperLayoutEvidenceService evidenceService =
            new PaperLayoutEvidenceService(policy, resolver);

    @Test
    void shouldDowngradeUnstructuredFormulaToRegionWithoutLeakingBrokenText() {
        DocumentBlock formula = new DocumentBlock(
                "formula", 1, new NormalizedBoundingBox(0.2, 0.3, 0.4, 0.08),
                DocumentBlockRole.FORMULA, 0, List.of("Method"), "x ? ? y", null, null,
                0.55, DocumentBlockContentMode.REGION);
        PaperLayoutArtifact artifact = artifact(formula);

        SelectionAnchor anchor = resolver.resolve(artifact, 1,
                List.of(formula.bbox()), "x y", SelectionAnchorKind.TEXT);
        LayoutEvidence evidence = evidenceService.toEvidence(artifact, formula, 1, true);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.REGION);
        assertThat(evidence.contentMode()).isEqualTo(DocumentBlockContentMode.REGION);
        assertThat(evidence.text()).isEqualTo("公式区域")
                .doesNotContain("未获得可信", "文本提取不可靠", "x ? ? y");
        assertThat(evidence.structuredContent()).isEmpty();
    }

    @Test
    void shouldKeepStructuredFormulaAddressable() {
        DocumentBlock formula = new DocumentBlock(
                "formula", 1, new NormalizedBoundingBox(0.2, 0.3, 0.4, 0.08),
                DocumentBlockRole.FORMULA, 0, List.of("Method"), "x = y + 1", "x = y + 1", null,
                0.9, DocumentBlockContentMode.STRUCTURED);
        PaperLayoutArtifact artifact = artifact(formula);

        SelectionAnchor anchor = resolver.resolve(artifact, 1,
                List.of(formula.bbox()), "x = y + 1", SelectionAnchorKind.TEXT);
        LayoutEvidence evidence = evidenceService.toEvidence(artifact, formula, 1, true);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.FORMULA);
        assertThat(evidence.structuredContent()).isEqualTo("x = y + 1");
    }

    private PaperLayoutArtifact artifact(DocumentBlock block) {
        return new PaperLayoutArtifact(1L, "e".repeat(64), "parser", 0.9,
                Instant.EPOCH, 1, List.of(block));
    }
}
