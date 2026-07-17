package com.research.assistant.dto.research;

import com.research.assistant.service.workbench.WorkbenchRunTrace;

import java.util.List;

public record ResearchSessionDetail(
        ResearchSessionSummary session,
        List<ResearchMessageView> messages,
        List<WorkbenchRunTrace> runs
) { }
