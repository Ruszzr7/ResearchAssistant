package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.util.List;

/** A deterministic, allow-listed client action produced only after evidence validation. */
public record WorkbenchAction(String actionId,
                              Type type,
                              Status status,
                              Long paperId,
                              String evidenceId,
                              int page,
                              String query,
                              String targetText,
                              List<NormalizedBoundingBox> targetBoxes,
                              String content,
                              String message) {

    public WorkbenchAction {
        actionId = safe(actionId);
        type = type == null ? Type.HIGHLIGHT : type;
        status = status == null ? Status.UNRESOLVED : status;
        evidenceId = safe(evidenceId);
        query = safe(query);
        targetText = safe(targetText);
        targetBoxes = targetBoxes == null ? List.of() : List.copyOf(targetBoxes);
        content = safe(content);
        message = safe(message);
        page = Math.max(0, page);
    }

    public enum Type {
        HIGHLIGHT,
        UNDERLINE,
        ADD_NOTE,
        ADD_COMMENT,
        NAVIGATE
    }

    public enum Status { READY, UNRESOLVED }

    public WorkbenchAction(String actionId,
                           Type type,
                           Status status,
                           Long paperId,
                           String evidenceId,
                           int page,
                           String query,
                           String targetText,
                           String message) {
        this(actionId, type, status, paperId, evidenceId, page, query, targetText,
                List.of(), "", message);
    }

    public WorkbenchAction(String actionId,
                           Type type,
                           Status status,
                           Long paperId,
                           String evidenceId,
                           int page,
                           String query,
                           String targetText,
                           List<NormalizedBoundingBox> targetBoxes,
                           String message) {
        this(actionId, type, status, paperId, evidenceId, page, query, targetText,
                targetBoxes, "", message);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
