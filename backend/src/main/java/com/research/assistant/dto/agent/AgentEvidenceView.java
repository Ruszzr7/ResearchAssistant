package com.research.assistant.dto.agent;

import com.research.assistant.service.agent.source.SourceLocator;

import java.util.List;
import java.util.Locale;

public record AgentEvidenceView(int citationNumber, String sourceObjectId, Long paperId, String quote,
                                String fullText, String evidenceKey, String contentType,
                                String textFormat, boolean textReliable, String formulaNumber,
                                List<SourceLocator> locators) {
    public AgentEvidenceView(int citationNumber, String sourceObjectId, Long paperId, String quote,
                             List<SourceLocator> locators) {
        this(citationNumber, sourceObjectId, paperId, quote, quote, "", "TEXT",
                "PLAIN_TEXT", true, "", locators);
    }

    public AgentEvidenceView(int citationNumber, String sourceObjectId, Long paperId, String quote,
                             String formulaNumber, List<SourceLocator> locators) {
        this(citationNumber, sourceObjectId, paperId, quote, quote, "", "TEXT",
                "PLAIN_TEXT", true, formulaNumber, locators);
    }

    public AgentEvidenceView {
        quote = quote == null ? "" : quote;
        fullText = fullText == null ? quote : fullText;
        evidenceKey = evidenceKey == null ? "" : evidenceKey.trim();
        contentType = contentType == null || contentType.isBlank()
                ? "TEXT" : contentType.trim().toUpperCase(Locale.ROOT);
        textFormat = textFormat == null || textFormat.isBlank()
                ? "PLAIN_TEXT" : textFormat.trim().toUpperCase(Locale.ROOT);
        formulaNumber = formulaNumber == null ? "" : formulaNumber.trim();
        locators = locators == null ? List.of() : List.copyOf(locators);
    }
}
