package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Deterministic source index derived from one exact layout-artifact version. */
public record PaperSourceIndex(int schemaVersion,
                               Long paperId,
                               String documentHash,
                               String parserVersion,
                               List<SourceAnchor> textAnchors,
                               List<EquationEntity> equations) {
    public static final int SCHEMA_VERSION = 1;

    public PaperSourceIndex {
        documentHash = documentHash == null ? "" : documentHash;
        parserVersion = parserVersion == null ? "" : parserVersion;
        textAnchors = textAnchors == null ? List.of() : List.copyOf(textAnchors);
        equations = equations == null ? List.of() : List.copyOf(equations);
    }
}
