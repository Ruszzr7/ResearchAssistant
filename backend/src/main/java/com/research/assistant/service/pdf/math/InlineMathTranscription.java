package com.research.assistant.service.pdf.math;

public record InlineMathTranscription(String blockId,
                                      int start,
                                      int end,
                                      String sourceText,
                                      String latex,
                                      MathTranscriptionStatus status,
                                      double confidence,
                                      String providerVersion,
                                      String message,
                                      boolean cached) {
    public InlineMathTranscription {
        blockId = blockId == null ? "" : blockId;
        sourceText = sourceText == null ? "" : sourceText;
        latex = latex == null ? "" : latex;
        status = status == null ? MathTranscriptionStatus.UNAVAILABLE : status;
        confidence = Math.max(0, Math.min(1, confidence));
        providerVersion = providerVersion == null ? "" : providerVersion;
        message = message == null ? "" : message;
    }
}
