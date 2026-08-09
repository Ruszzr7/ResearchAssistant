package com.research.assistant.service.workbench;

/** A deterministic, allow-listed client action produced only after evidence validation. */
public record WorkbenchAction(String actionId,
                              Type type,
                              Status status,
                              Long paperId,
                              String evidenceId,
                              int page,
                              String query,
                              String targetText,
                              String message) {

    public WorkbenchAction {
        actionId = safe(actionId);
        type = type == null ? Type.HIGHLIGHT : type;
        status = status == null ? Status.UNRESOLVED : status;
        evidenceId = safe(evidenceId);
        query = safe(query);
        targetText = safe(targetText);
        message = safe(message);
        page = Math.max(0, page);
    }

    public enum Type { HIGHLIGHT }

    public enum Status { READY, UNRESOLVED }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
