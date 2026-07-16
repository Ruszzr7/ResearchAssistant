package com.research.assistant.service.workbench;

/** User-facing intent hint. The router still owns the final workflow choice. */
public enum WorkbenchIntent {
    AUTO,
    ASK_SELECTION,
    ANALYZE_PAPER,
    SUGGEST_ANNOTATION,
    COMPARE_PAPERS,
    FIND_RESEARCH_GAPS
}
