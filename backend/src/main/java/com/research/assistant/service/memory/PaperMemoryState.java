package com.research.assistant.service.memory;

import java.time.Instant;

/** Read model for one persisted paper-memory revision. */
public record PaperMemoryState(Long id,
                               Long paperId,
                               String documentHash,
                               String layoutParserVersion,
                               String schemaVersion,
                               String status,
                               int revision,
                               PaperStructure structure,
                               String lastErrorCode,
                               Instant generatedAt,
                               Instant updatedAt) {
}
