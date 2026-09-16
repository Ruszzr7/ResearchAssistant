package com.research.assistant.dto.agent;

import com.research.assistant.service.agent.source.CitationBinding;

import java.util.List;

public record AgentTurnResult(String turnId, String runId, String status, String message,
                              List<CitationBinding> citations, List<AgentEvidenceView> evidence,
                              List<AgentPendingAction> pendingActions, String outputMode) {
    public AgentTurnResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
        outputMode = outputMode == null || outputMode.isBlank() ? "CONTENT" : outputMode;
    }

    public AgentTurnResult(String turnId, String runId, String status, String message,
                           List<CitationBinding> citations, List<AgentEvidenceView> evidence) {
        this(turnId, runId, status, message, citations, evidence, List.of(), "CONTENT");
    }

    public AgentTurnResult(String turnId, String runId, String status, String message,
                           List<CitationBinding> citations, List<AgentEvidenceView> evidence,
                           List<AgentPendingAction> pendingActions) {
        this(turnId, runId, status, message, citations, evidence, pendingActions,
                pendingActions == null || pendingActions.isEmpty() ? "CONTENT" : "ACTION_ONLY");
    }
}
