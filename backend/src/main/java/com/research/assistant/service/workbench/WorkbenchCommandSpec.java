package com.research.assistant.service.workbench;

/** A server-parsed, allow-listed PDF operation. The model may help select evidence, not the tool. */
public record WorkbenchCommandSpec(Type type, String target, ReferenceMode referenceMode) {
    public WorkbenchCommandSpec {
        type = type == null ? Type.HIGHLIGHT : type;
        target = target == null ? "" : target.trim();
        referenceMode = referenceMode == null ? ReferenceMode.EXPLICIT : referenceMode;
    }

    public enum Type { HIGHLIGHT }

    public enum ReferenceMode { EXPLICIT, PRIOR_REFERENT }
}
