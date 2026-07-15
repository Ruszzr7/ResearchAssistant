package com.research.assistant.service.pdf.layout;

import java.time.Instant;
import java.util.List;

/**
 * Versioned, page-addressable layout representation for one paper PDF.
 */
public record PaperLayoutArtifact(Long paperId,
                                  String documentHash,
                                  String parserVersion,
                                  double layoutConfidence,
                                  Instant generatedAt,
                                  int pageCount,
                                  List<DocumentBlock> blocks) {

    public PaperLayoutArtifact {
        documentHash = documentHash == null ? "" : documentHash;
        parserVersion = parserVersion == null ? "" : parserVersion;
        layoutConfidence = Math.max(0, Math.min(1, layoutConfidence));
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
