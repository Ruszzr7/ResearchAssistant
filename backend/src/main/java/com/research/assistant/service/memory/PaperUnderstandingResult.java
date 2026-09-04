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
                                       PaperGlobalProfile profile,
                                       List<PaperLayoutRecovery> recoveries) {

    public PaperUnderstandingResult {
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
        recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
    }

    public PaperUnderstandingResult(Long memoryId, Long paperId, String status,
                                    int totalChunks, int completedChunks, int failedChunks,
                                    int promptTokens, int completionTokens,
                                    List<PaperChunkSummary> summaries, PaperGlobalProfile profile) {
        this(memoryId, paperId, status, totalChunks, completedChunks, failedChunks,
                promptTokens, completionTokens, summaries, profile, List.of());
    }

    public boolean usable() {
        return "READY".equals(status) && completedChunks > 0 && profile != null;
    }
}
