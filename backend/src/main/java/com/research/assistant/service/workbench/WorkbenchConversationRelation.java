package com.research.assistant.service.workbench;

/** Separates chat-history continuity from reuse of the previous retrieval focus. */
public enum WorkbenchConversationRelation {
    NONE,
    INDEPENDENT,
    FOLLOW_UP
}
