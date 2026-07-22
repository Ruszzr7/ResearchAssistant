package com.research.assistant.service.pdf.math;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalUnicodeMathTranscriptionProviderTest {

    private final LocalUnicodeMathTranscriptionProvider provider =
            new LocalUnicodeMathTranscriptionProvider();

    @Test
    void convertsCommonUnicodeMathWithoutNetworkOrOcr() {
        MathTranscriptionCandidate result = provider.transcribe(request(
                "p_c ∈ ℂ and ||p_c||² ≤ μ_k"));

        assertThat(result.status()).isEqualTo(MathTranscriptionStatus.APPROXIMATE);
        assertThat(result.latex()).contains("p_c", "\\in", "\\mathbb{C}",
                "^{2}", "\\le", "\\mu");
        assertThat(result.message()).contains("回原页核对");
    }

    @Test
    void reusesExistingLatexAsReady() {
        MathTranscriptionCandidate result = provider.transcribe(request("$$\\frac{x}{y}$$"));

        assertThat(result.status()).isEqualTo(MathTranscriptionStatus.READY);
        assertThat(result.latex()).isEqualTo("\\frac{x}{y}");
    }

    @Test
    void refusesToGuessWhenTheTextLayerIsMissingGlyphs() {
        MathTranscriptionCandidate result = provider.transcribe(request("p_� = 1"));

        assertThat(result.status()).isEqualTo(MathTranscriptionStatus.UNAVAILABLE);
        assertThat(result.latex()).isBlank();
    }

    private MathTranscriptionRequest request(String source) {
        return new MathTranscriptionRequest(1L, "a".repeat(64), "parser", 1,
                "body", 0, source.length(), source);
    }
}
