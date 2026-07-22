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
                               Integer charEnd,
                               List<ClientContentSegment> contentSegments) {

    public ClientTextAnchor {
        version = Math.max(1, version);
        page = Math.max(1, page);
        documentFingerprint = documentFingerprint == null ? "" : documentFingerprint;
        textMapVersion = Math.max(1, textMapVersion);
        ranges = ranges == null ? List.of() : List.copyOf(ranges);
        contentSegments = contentSegments == null ? List.of() : List.copyOf(contentSegments);
        engine = engine == null || engine.isBlank() ? "PDFJS" : engine.strip().toUpperCase();
        if ("PDFIUM".equals(engine)) {
            if (charStart == null || charEnd == null || charStart < 0 || charEnd < charStart) {
                throw new IllegalArgumentException("invalid PDFium character range");
            }
            for (ClientContentSegment segment : contentSegments) {
                if (segment.charStart() < charStart || segment.charEnd() > charEnd) {
                    throw new IllegalArgumentException("client content segment exceeds anchor range");
                }
            }
        } else {
            charStart = null;
            charEnd = null;
            contentSegments = List.of();
        }
    }
}
