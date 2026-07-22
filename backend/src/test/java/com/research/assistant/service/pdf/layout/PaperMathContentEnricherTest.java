package com.research.assistant.service.pdf.layout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperMathContentEnricherTest {

    private final PaperMathContentEnricher enricher = new PaperMathContentEnricher();

    @Test
    void detectsTheMathDenseProsePatternWithoutOcr() {
        String text = "where p_c ∈ C^{N_t×1} and p_k ∈ C^{N_t×1} are the precoders, "
                + "satisfying ||p_c||² = ||p_k||² = 1, ∀k ∈ K. The coefficient 0 ≤ t ≤ 1 "
                + "and Σ_{k=1}^{K} μ_k = 1 specify the power distribution.";

        MathContentProfile profile = enricher.detect(text);

        assertThat(profile.level()).isEqualTo(MathContentLevel.MATH_RICH);
        assertThat(profile.signalCount()).isGreaterThanOrEqualTo(8);
        assertThat(profile.density()).isGreaterThan(0.15);
        assertThat(profile.fragments()).isNotEmpty();
        assertThat(profile.fragments()).allSatisfy(fragment -> {
            assertThat(fragment.sourceText())
                    .isEqualTo(text.substring(fragment.start(), fragment.end()));
            assertThat(fragment.signals()).isNotEmpty();
        });
    }

    @Test
    void doesNotClassifyOrdinaryTechnicalProseAsMathRich() {
        MathContentProfile profile = enricher.detect(
                "The proposed architecture improves reliability in dense urban networks.");

        assertThat(profile.level()).isEqualTo(MathContentLevel.NONE);
        assertThat(profile.fragments()).isEmpty();
    }

    @Test
    void persistsProfilesInTheVersionedLayoutArtifact() {
        DocumentBlock block = new DocumentBlock("body", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.1), DocumentBlockRole.BODY,
                0, List.of(), "For every k ∈ K, μ_k ≥ 0.", null, null, 0.9);
        PaperLayoutArtifact enriched = enricher.enrich(new PaperLayoutArtifact(
                1L, "a".repeat(64), "parser+semantic", 0.9, Instant.now(), 1, List.of(block)));

        assertThat(enriched.parserVersion()).isEqualTo("parser+semantic+inline-math-v1");
        assertThat(enriched.blocks().get(0).mathProfile().detectorVersion())
                .isEqualTo("inline-math-v1");
        assertThat(enriched.blocks().get(0).mathProfile().fragments()).isNotEmpty();
    }
}
