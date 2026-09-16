package com.research.assistant.service.agent.action;

import java.time.Instant;
import java.util.List;

public record ActionTicketPayload(String runId, String toolCallId, long paperId, String documentHash,
                                  String sourceObjectId, PaperActionType actionType, String content,
                                  String color, Instant expiresAt, String nonce,
                                  List<String> locatorIds) {
    public ActionTicketPayload {
        locatorIds = locatorIds == null ? List.of() : List.copyOf(locatorIds);
    }
}
