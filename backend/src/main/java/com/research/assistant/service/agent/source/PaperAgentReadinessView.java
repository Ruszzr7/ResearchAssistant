package com.research.assistant.service.agent.source;

public record PaperAgentReadinessView(
        long paperId,
        String status,
        String statusText,
        boolean fileReady,
        boolean localSourceReady,
        boolean searchReady,
        boolean profileReady,
        boolean visualReady,
        boolean fallbackMode,
        boolean conversationReady,
        int understandingAttempts
) { }
