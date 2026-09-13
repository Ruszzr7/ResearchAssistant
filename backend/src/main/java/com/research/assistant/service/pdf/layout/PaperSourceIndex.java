package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Deterministic source index derived from one exact layout-artifact version. */
public record PaperSourceIndex(int schemaVersion,
                               Long paperId,
                               String documentHash,
                               String parserVersion,
                               List<SourceAnchor> textAnchors,
                               List<EquationEntity> equations,
                               List<PaperSourceUnit> sourceUnits,
                               List<PaperSourceContinuation> continuations) {
    /** Figure captions and figure discussions are structurally separated in v7. */
    public static final int SCHEMA_VERSION = 7;

    public PaperSourceIndex {
        documentHash = documentHash == null ? "" : documentHash;
        parserVersion = parserVersion == null ? "" : parserVersion;
        textAnchors = textAnchors == null ? List.of() : List.copyOf(textAnchors);
        equations = equations == null ? List.of() : List.copyOf(equations);
        sourceUnits = sourceUnits == null ? List.of() : List.copyOf(sourceUnits);
        continuations = continuations == null ? List.of() : List.copyOf(continuations);
    }

    public PaperSourceIndex(int schemaVersion,
                            Long paperId,
                            String documentHash,
                            String parserVersion,
                            List<SourceAnchor> textAnchors,
                            List<EquationEntity> equations) {
        this(schemaVersion, paperId, documentHash, parserVersion, textAnchors, equations,
                List.of(), List.of());
    }

    public PaperSourceIndex(int schemaVersion,
                            Long paperId,
                            String documentHash,
                            String parserVersion,
                            List<SourceAnchor> textAnchors,
                            List<EquationEntity> equations,
                            List<PaperSourceUnit> sourceUnits) {
        this(schemaVersion, paperId, documentHash, parserVersion, textAnchors, equations,
                sourceUnits, List.of());
    }
}
