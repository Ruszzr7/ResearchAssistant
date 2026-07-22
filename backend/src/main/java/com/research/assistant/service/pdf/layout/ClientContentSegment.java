package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Engine-derived auxiliary segment. The PDFium character range remains authoritative. */
public record ClientContentSegment(ClientContentSegmentType type,
                                   int charStart,
                                   int charEnd,
                                   String sourceText,
                                   List<String> fonts,
                                   NormalizedBoundingBox bbox) {
    public ClientContentSegment {
        type = type == null ? ClientContentSegmentType.TEXT : type;
        sourceText = sourceText == null ? "" : sourceText;
        fonts = fonts == null ? List.of() : List.copyOf(fonts);
        if (charStart < 0 || charEnd < charStart) {
            throw new IllegalArgumentException("invalid client content segment range");
        }
    }
}
