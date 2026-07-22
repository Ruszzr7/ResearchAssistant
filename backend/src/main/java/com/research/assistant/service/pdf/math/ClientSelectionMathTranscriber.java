package com.research.assistant.service.pdf.math;

import com.research.assistant.service.pdf.layout.ClientContentSegment;
import com.research.assistant.service.pdf.layout.ClientContentSegmentType;
import com.research.assistant.service.pdf.layout.ClientTextAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Transcribes PDFium-selected math hints without promoting them to canonical PDF evidence. */
@Service
public class ClientSelectionMathTranscriber {

    private final List<InlineMathTranscriptionProvider> providers;

    public ClientSelectionMathTranscriber(List<InlineMathTranscriptionProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public List<InlineMathTranscription> transcribe(SelectionAnchor anchor) {
        ClientTextAnchor client = anchor == null ? null : anchor.clientTextAnchor();
        if (client == null || !"PDFIUM".equals(client.engine())) return List.of();
        InlineMathTranscriptionProvider provider = providers.stream().findFirst().orElse(null);
        return client.contentSegments().stream()
                .filter(segment -> segment.type() != ClientContentSegmentType.TEXT)
                .limit(48)
                .map(segment -> transcribe(anchor, segment, provider))
                .toList();
    }

    private InlineMathTranscription transcribe(SelectionAnchor anchor,
                                                ClientContentSegment segment,
                                                InlineMathTranscriptionProvider provider) {
        String blockId = "pdfium-p" + anchor.page();
        if (provider == null || !containsSource(anchor.anchorText(), segment.sourceText())) {
            return new InlineMathTranscription(blockId, segment.charStart(), segment.charEnd(),
                    segment.sourceText(), "", MathTranscriptionStatus.UNAVAILABLE, 0,
                    provider == null ? "none" : provider.version(),
                    "PDFium 数学片段无法与当前选区原文复核", false);
        }
        try {
            MathTranscriptionCandidate candidate = provider.transcribe(new MathTranscriptionRequest(
                    anchor.paperId(), anchor.documentHash(), anchor.parserVersion(), anchor.page(),
                    blockId, segment.charStart(), segment.charEnd(), segment.sourceText()));
            return new InlineMathTranscription(blockId, segment.charStart(), segment.charEnd(),
                    segment.sourceText(), candidate.latex(), candidate.status(), candidate.confidence(),
                    provider.version(), candidate.message(), false);
        } catch (RuntimeException exception) {
            return new InlineMathTranscription(blockId, segment.charStart(), segment.charEnd(),
                    segment.sourceText(), "", MathTranscriptionStatus.UNAVAILABLE, 0,
                    provider.version(), "本地数学转写失败，需回原页核对", false);
        }
    }

    private boolean containsSource(String selection, String source) {
        String normalizedSelection = normalize(selection);
        String normalizedSource = normalize(source);
        return !normalizedSource.isBlank() && normalizedSelection.contains(normalizedSource);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").strip();
    }
}
