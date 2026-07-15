package com.research.assistant.service.pdf.layout;

import java.io.File;

/** Builds a deterministic layout artifact from a PDF file. */
public interface PaperLayoutParser {

    PaperLayoutArtifact parse(Long paperId, File file);

    /**
     * Parses a file whose fingerprint has already been calculated by the
     * caller. Implementations may override this to avoid hashing twice.
     */
    default PaperLayoutArtifact parse(Long paperId, File file, String documentHash) {
        return parse(paperId, file);
    }

    String parserVersion();
}
