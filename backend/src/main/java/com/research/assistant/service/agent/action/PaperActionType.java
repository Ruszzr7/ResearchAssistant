package com.research.assistant.service.agent.action;

public enum PaperActionType {
    JUMP,
    HIGHLIGHT,
    UNDERLINE,
    NOTE,
    COMMENT;

    public boolean createsAnnotation() { return this != JUMP; }
}
