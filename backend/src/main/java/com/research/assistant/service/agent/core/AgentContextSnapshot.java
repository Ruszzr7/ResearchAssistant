package com.research.assistant.service.agent.core;

import com.research.assistant.service.agent.source.PaperSourceCatalog;

import java.util.List;
import java.util.Set;

public record AgentContextSnapshot(long sessionId, Long paperId, PaperSourceCatalog sourceCatalog,
                                   boolean profileAvailable,
                                   List<AgentChatEntry> messages, Set<String> preReadSourceIds,
                                   String snapshotJson) {
    public AgentContextSnapshot {
        messages = messages == null ? List.of() : List.copyOf(messages);
        preReadSourceIds = preReadSourceIds == null ? Set.of() : Set.copyOf(preReadSourceIds);
    }

    /** Compatibility constructor for focused callers that do not provide profile state. */
    public AgentContextSnapshot(long sessionId, Long paperId, PaperSourceCatalog sourceCatalog,
                                List<AgentChatEntry> messages, Set<String> preReadSourceIds,
                                String snapshotJson) {
        this(sessionId, paperId, sourceCatalog, false, messages, preReadSourceIds, snapshotJson);
    }
}
