package com.research.assistant.service.workbench;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bounded text extracted locally from a user-selected chat attachment. */
public record WorkbenchAttachment(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 120) String mimeType,
        @NotBlank @Size(max = 12_000) String content,
        boolean truncated) {

    public WorkbenchAttachment {
        name = name == null ? "" : name.trim();
        mimeType = mimeType == null ? "application/octet-stream" : mimeType.trim();
        content = content == null ? "" : content.trim();
    }
}
