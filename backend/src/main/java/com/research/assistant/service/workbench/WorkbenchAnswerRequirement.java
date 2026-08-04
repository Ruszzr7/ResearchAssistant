package com.research.assistant.service.workbench;

import java.util.List;

/** A bounded, evidence-aware item that the final answer must explicitly address. */
public record WorkbenchAnswerRequirement(String id,
                                         Type type,
                                         String content,
                                         boolean required,
                                         List<WorkbenchAnswerBlock.Citation> evidenceRefs) {

    public WorkbenchAnswerRequirement {
        id = id == null ? "" : id.trim();
        type = type == null ? Type.DIRECT : type;
        content = content == null ? "" : content.trim();
        evidenceRefs = evidenceRefs == null ? List.of() : evidenceRefs.stream()
                .filter(ref -> ref != null && !ref.evidenceId().isBlank() && !ref.quote().isBlank())
                .distinct()
                .toList();
    }

    public enum Type {
        DIRECT,
        CONTEXT,
        LIMIT
    }
}
