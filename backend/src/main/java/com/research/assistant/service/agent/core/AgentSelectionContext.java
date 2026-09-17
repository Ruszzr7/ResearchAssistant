package com.research.assistant.service.agent.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Host-validated capabilities for the current PDF selection.
 *
 * The selected text is still supplied as paper data in the current user
 * message, but its provenance and source handles are established by the host
 * after document-hash and catalog validation.  Keeping this capability record
 * separate from the text prevents the model from having to infer whether an
 * ID is actionable from a quoted passage.
 */
public record AgentSelectionContext(
        String selectionId,
        long paperId,
        String documentHash,
        int pageNumber,
        String contentType,
        List<String> sourceObjectIds
) {
    public AgentSelectionContext {
        if (selectionId == null || selectionId.isBlank()) {
            throw new IllegalArgumentException("selectionId is required");
        }
        if (paperId <= 0 || pageNumber <= 0) {
            throw new IllegalArgumentException("paperId and pageNumber must be positive");
        }
        if (documentHash == null || documentHash.isBlank()) {
            throw new IllegalArgumentException("documentHash is required");
        }
        contentType = contentType == null || contentType.isBlank()
                ? "TEXT" : contentType.trim().toUpperCase();
        Set<String> unique = new LinkedHashSet<>();
        if (sourceObjectIds != null) {
            for (String sourceObjectId : sourceObjectIds) {
                if (sourceObjectId != null && !sourceObjectId.isBlank()) {
                    unique.add(sourceObjectId.trim());
                }
            }
        }
        sourceObjectIds = Collections.unmodifiableList(List.copyOf(unique));
    }

    public boolean actionable() {
        return !sourceObjectIds.isEmpty();
    }
}
