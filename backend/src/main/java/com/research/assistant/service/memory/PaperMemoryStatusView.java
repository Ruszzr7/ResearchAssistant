package com.research.assistant.service.memory;

import java.time.Instant;

/** Safe frontend view of paper-memory readiness and progress. */
public record PaperMemoryStatusView(Long paperId,
                                    Long memoryId,
                                    int revision,
                                    String status,
                                    String stageText,
                                    int progress,
                                    int totalChunks,
                                    int completedChunks,
                                    int failedChunks,
                                    int promptTokens,
                                    int completionTokens,
                                    boolean structureReady,
                                    boolean profileReady,
                                    boolean canStart,
                                    boolean canRetry,
                                    String lastErrorCode,
                                    PaperGlobalProfile profile,
                                    Instant updatedAt) {
}
