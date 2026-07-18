package com.research.assistant.service.memory;

import java.time.Instant;
import java.util.List;

/** Deduplicated long-term claim memory; it is a retrieval hint, never direct answer evidence. */
public record PaperMemoryObservation(long id,
                                     String claimText,
                                     List<PaperMemoryEvidenceRef> evidenceRefs,
                                     String sourceRunId,
                                     String sourceConversationId,
                                     int confirmationCount,
                                     Instant lastConfirmedAt) {
    public PaperMemoryObservation {
        claimText = safe(claimText);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        sourceRunId = safe(sourceRunId);
        sourceConversationId = safe(sourceConversationId);
        confirmationCount = Math.max(1, confirmationCount);
        lastConfirmedAt = lastConfirmedAt == null ? Instant.now() : lastConfirmedAt;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
