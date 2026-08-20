package com.research.assistant.service.workbench;

import java.util.List;

/** One authoritative decision describing how a workbench turn must be handled. */
public record WorkbenchTurnRoute(Type type,
                                 WorkbenchCommandSpec command,
                                 boolean currentSelection,
                                 boolean inheritPreviousFocus,
                                 boolean retrievePaperEvidence,
                                 boolean callModel,
                                 double confidence,
                                 List<String> reasons) {

    public WorkbenchTurnRoute {
        type = type == null ? Type.PAPER_QA : type;
        confidence = Math.max(0, Math.min(1, confidence));
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean action() {
        return switch (type) {
            case SELECTION_ACTION, PAPER_ACTION, FOLLOW_UP_ACTION -> true;
            default -> false;
        };
    }

    public boolean questionAnswering() {
        return !action();
    }

    public WorkbenchConversationRelation conversationRelation() {
        return inheritPreviousFocus ? WorkbenchConversationRelation.FOLLOW_UP
                : WorkbenchConversationRelation.INDEPENDENT;
    }

    public enum Type {
        SELECTION_QA,
        SELECTION_ACTION,
        PAPER_QA,
        PAPER_ACTION,
        FOLLOW_UP_QA,
        FOLLOW_UP_ACTION,
        GENERAL_CHAT
    }
}
