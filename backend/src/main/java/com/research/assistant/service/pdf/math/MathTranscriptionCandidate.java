package com.research.assistant.service.pdf.math;

public record MathTranscriptionCandidate(MathTranscriptionStatus status,
                                         String latex,
                                         double confidence,
                                         String message) {
    public MathTranscriptionCandidate {
        status = status == null ? MathTranscriptionStatus.UNAVAILABLE : status;
        latex = latex == null ? "" : latex;
        confidence = Math.max(0, Math.min(1, confidence));
        message = message == null ? "" : message;
    }
}
