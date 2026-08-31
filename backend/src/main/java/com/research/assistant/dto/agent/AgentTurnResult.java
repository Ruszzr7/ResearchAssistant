package com.research.assistant.dto.agent;

import com.research.assistant.service.agent.source.CitationBinding;

import java.util.List;

public record AgentTurnResult(String turnId, String runId, String status, String message,
                              List<CitationBinding> citations, List<AgentEvidenceView> evidence,
                              List<AgentPendingAction> pendingActions) {
    public AgentTurnResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
    }

    public AgentTurnResult(String turnId, String runId, String status, String message,
                           List<CitationBinding> citations, List<AgentEvidenceView> evidence) {
        this(turnId, runId, status, message, citations, evidence, List.of());
    }
}
