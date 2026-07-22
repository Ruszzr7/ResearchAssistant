package com.research.assistant.service.pdf.math;

public interface InlineMathTranscriptionProvider {
    String version();
    MathTranscriptionCandidate transcribe(MathTranscriptionRequest request);
}
