package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

/** Ephemeral, server-rendered view of one selected PDF region. Never persisted in a run trace. */
public record WorkbenchSelectionVisualEvidence(byte[] png,
                                               int page,
                                               NormalizedBoundingBox bbox,
                                               String selectionText,
                                               String message) {
    public WorkbenchSelectionVisualEvidence {
        png = png == null ? new byte[0] : png.clone();
        selectionText = selectionText == null ? "" : selectionText;
        message = message == null ? "" : message;
    }

    @Override
    public byte[] png() {
        return png.clone();
    }

    public boolean available() {
        return png.length > 0;
    }
}
