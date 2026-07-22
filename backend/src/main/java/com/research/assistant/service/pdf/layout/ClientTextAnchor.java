package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Versioned, viewer-local character anchor. It never replaces server-side evidence resolution. */
public record ClientTextAnchor(int version,
                               int page,
                               String documentFingerprint,
                               int textMapVersion,
                               List<ClientTextRange> ranges,
                               String engine,
                               Integer charStart,
                               Integer charEnd) {

    public ClientTextAnchor {
        version = Math.max(1, version);
        page = Math.max(1, page);
        documentFingerprint = documentFingerprint == null ? "" : documentFingerprint;
        textMapVersion = Math.max(1, textMapVersion);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
        engine = engine == null || engine.isBlank() ? "PDFJS" : engine.strip().toUpperCase();
        if ("PDFIUM".equals(engine)) {
            if (charStart == null || charEnd == null || charStart < 0 || charEnd < charStart) {
                throw new IllegalArgumentException("invalid PDFium character range");
            }
        } else {
            charStart = null;
            charEnd = null;
        }
    }
}
