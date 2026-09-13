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
    public static final int MAX_USER_MESSAGE_CHARACTERS = 2_000;
    public static final int MAX_ATTACHMENTS = 2;

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
        if (userMessage != null && userMessage.length() > MAX_USER_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("输入内容过长");
        }
        if (attachmentIds.size() + formulaAttachmentIds.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("每条消息最多添加 2 个附件");
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
