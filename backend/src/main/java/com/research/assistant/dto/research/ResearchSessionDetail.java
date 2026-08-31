package com.research.assistant.dto.research;

import java.util.List;

public record ResearchSessionDetail(
        ResearchSessionSummary session,
        List<ResearchMessageView> messages
) { }
