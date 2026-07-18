package com.research.assistant.service.memory;

import com.research.assistant.service.workbench.WorkbenchEvidenceGate;

import java.time.Instant;
import java.util.List;

/** One server-owned, evidence-approved question/answer turn. */
public record PaperConversationTurn(long id,
                                    String sourceRunId,
                                    String question,
                                    String answer,
                                    List<String> selectionBlockIds,
                                    List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                    List<PaperMemoryEvidenceRef> evidenceRefs,
                                    Instant createdAt) {
    public PaperConversationTurn {
        sourceRunId = safe(sourceRunId);
        question = safe(question);
        answer = safe(answer);
        selectionBlockIds = copy(selectionBlockIds);
        claims = copy(claims);
        evidenceRefs = copy(evidenceRefs);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
