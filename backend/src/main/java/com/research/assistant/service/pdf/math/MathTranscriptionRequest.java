package com.research.assistant.service.pdf.math;

public record MathTranscriptionRequest(Long paperId,
                                       String documentHash,
                                       String parserVersion,
                                       int page,
                                       String blockId,
                                       int start,
                                       int end,
                                       String sourceText) {
}
