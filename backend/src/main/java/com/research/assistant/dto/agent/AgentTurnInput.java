package com.research.assistant.dto.agent;

import java.util.List;

public record AgentTurnInput(
        long conversationId,
        Long primaryPaperId,
        String userMessage,
        AgentExplicitAction explicitAction,
        AgentSelectedContent selectedContent,
        List<String> attachmentIds,
        List<String> formulaAttachmentIds,
        AgentUiContext uiContext,
        String clientRequestId,
        String resumeRunId
) {
    public AgentTurnInput {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        formulaAttachmentIds = formulaAttachmentIds == null ? List.of() : List.copyOf(formulaAttachmentIds);
        userMessage = normalize(userMessage);
        clientRequestId = normalize(clientRequestId);
        resumeRunId = normalize(resumeRunId);
        if (conversationId <= 0) throw new IllegalArgumentException("conversationId must be positive");
        if (clientRequestId == null) throw new IllegalArgumentException("clientRequestId is required");
        if (userMessage == null && explicitAction == null) {
            throw new IllegalArgumentException("userMessage or explicitAction is required");
        }
        if (attachmentIds.size() > 3) throw new IllegalArgumentException("at most 3 attachments are allowed");
        if (formulaAttachmentIds.size() > 8) throw new IllegalArgumentException("at most 8 formula attachments are allowed");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
