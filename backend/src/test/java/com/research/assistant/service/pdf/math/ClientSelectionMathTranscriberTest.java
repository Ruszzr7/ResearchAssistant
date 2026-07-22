package com.research.assistant.service.pdf.math;

import com.research.assistant.service.pdf.layout.ClientContentSegment;
import com.research.assistant.service.pdf.layout.ClientContentSegmentType;
import com.research.assistant.service.pdf.layout.ClientTextAnchor;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSelectionMathTranscriberTest {

    @Test
    void transcribesOnlyMathSegmentsAndPreservesPdfiumRanges() {
        InlineMathTranscriptionProvider provider = new LocalUnicodeMathTranscriptionProvider();
        ClientSelectionMathTranscriber service = new ClientSelectionMathTranscriber(List.of(provider));
        ClientTextAnchor client = new ClientTextAnchor(2, 3, "fingerprint", 1, List.of(),
                "PDFIUM", 100, 140, List.of(
                new ClientContentSegment(ClientContentSegmentType.TEXT, 100, 105, "where ", List.of(), null),
                new ClientContentSegment(ClientContentSegmentType.INLINE_MATH, 106, 114,
                        "p_c ∈ C", List.of("CMMI10"),
                        new NormalizedBoundingBox(0.55, 0.5, 0.12, 0.02))));
        SelectionAnchor anchor = new SelectionAnchor(7L, 3, List.of(
                new NormalizedBoundingBox(0.55, 0.5, 0.4, 0.05)),
                "where p_c ∈ C are precoders", List.of("p3-b1"), null,
                SelectionAnchorKind.TEXT, 0.9, "a".repeat(64), "parser", client);

        List<InlineMathTranscription> result = service.transcribe(anchor);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceText()).isEqualTo("p_c ∈ C");
        assertThat(result.get(0).start()).isEqualTo(106);
        assertThat(result.get(0).status()).isEqualTo(MathTranscriptionStatus.APPROXIMATE);
    }

    @Test
    void rejectsAClientMathHintThatIsNotPresentInSelectedSource() {
        ClientSelectionMathTranscriber service = new ClientSelectionMathTranscriber(
                List.of(new LocalUnicodeMathTranscriptionProvider()));
        ClientTextAnchor client = new ClientTextAnchor(2, 1, "fingerprint", 1, List.of(),
                "PDFIUM", 0, 20, List.of(new ClientContentSegment(
                ClientContentSegmentType.INLINE_MATH, 0, 5, "forged", List.of(), null)));
        SelectionAnchor anchor = new SelectionAnchor(7L, 1, List.of(
                new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.02)), "actual text", List.of(), null,
                SelectionAnchorKind.REGION, 0.2, "a".repeat(64), "parser", client);

        assertThat(service.transcribe(anchor).get(0).status())
                .isEqualTo(MathTranscriptionStatus.UNAVAILABLE);
    }
}
