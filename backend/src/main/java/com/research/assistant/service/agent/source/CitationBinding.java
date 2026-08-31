package com.research.assistant.service.agent.source;

import java.util.List;

public record CitationBinding(
        String citationId,
        int citationNumber,
        int answerStart,
        int answerEnd,
        String sourceObjectId,
        String quote,
        List<String> locatorIds
) { }
