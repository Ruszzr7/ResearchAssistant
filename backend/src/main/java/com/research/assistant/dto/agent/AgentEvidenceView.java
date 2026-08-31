package com.research.assistant.dto.agent;

import com.research.assistant.service.agent.source.SourceLocator;

import java.util.List;

public record AgentEvidenceView(int citationNumber, String sourceObjectId, Long paperId, String quote,
                                String formulaNumber, List<SourceLocator> locators) {
    public AgentEvidenceView(int citationNumber, String sourceObjectId, Long paperId, String quote,
                             List<SourceLocator> locators) {
        this(citationNumber, sourceObjectId, paperId, quote, "", locators);
    }

    public AgentEvidenceView {
        formulaNumber = formulaNumber == null ? "" : formulaNumber.trim();
        locators = locators == null ? List.of() : List.copyOf(locators);
    }
}
