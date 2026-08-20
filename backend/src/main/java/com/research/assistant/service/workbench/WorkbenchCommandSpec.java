package com.research.assistant.service.workbench;

/** A server-parsed, allow-listed PDF operation. The model may help select evidence, not the tool. */
public record WorkbenchCommandSpec(Type type,
                                   String target,
                                   ReferenceMode referenceMode,
                                   String content,
                                   Integer pageNumber) {
    public WorkbenchCommandSpec {
        type = type == null ? Type.HIGHLIGHT : type;
        target = target == null ? "" : target.trim();
        referenceMode = referenceMode == null ? ReferenceMode.EXPLICIT : referenceMode;
        content = content == null ? "" : content.trim();
        pageNumber = pageNumber == null || pageNumber < 1 ? null : pageNumber;
    }

    public WorkbenchCommandSpec(Type type, String target, ReferenceMode referenceMode,
                                String content) {
        this(type, target, referenceMode, content, null);
    }

    public WorkbenchCommandSpec(Type type, String target, ReferenceMode referenceMode) {
        this(type, target, referenceMode, "", null);
    }

    public enum Type {
        HIGHLIGHT,
        UNDERLINE,
        ADD_NOTE,
        ADD_COMMENT,
        NAVIGATE
    }

    public enum ReferenceMode { CURRENT_SELECTION, EXPLICIT, PRIOR_REFERENT }
}
