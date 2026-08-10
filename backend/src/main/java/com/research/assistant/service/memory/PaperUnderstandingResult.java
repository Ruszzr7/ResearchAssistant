package com.research.assistant.service.memory;

import java.util.List;

/** Result of one resumable paper-understanding pass. */
public record PaperUnderstandingResult(Long memoryId,
                                       Long paperId,
                                       String status,
                                       int totalChunks,
                                       int completedChunks,
                                       int failedChunks,
                                       int promptTokens,
                                       int completionTokens,
                                       List<PaperChunkSummary> summaries,
                                       PaperGlobalProfile profile) {

    public PaperUnderstandingResult {
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }

    public boolean usable() {
        return "READY".equals(status) && completedChunks > 0 && profile != null;
    }
}
