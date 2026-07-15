package com.research.assistant.service.pdf.layout;

import java.io.File;

/** Builds a deterministic layout artifact from a PDF file. */
public interface PaperLayoutParser {

    PaperLayoutArtifact parse(Long paperId, File file);

    String parserVersion();
}
