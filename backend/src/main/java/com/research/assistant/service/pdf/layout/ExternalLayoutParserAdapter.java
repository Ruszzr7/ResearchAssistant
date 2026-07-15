package com.research.assistant.service.pdf.layout;

import java.io.File;

/** Optional page-addressable fallback parser, normally backed by GROBID or MinerU. */
public interface ExternalLayoutParserAdapter {

    boolean enabled();

    String policyFingerprint();

    ExternalLayoutParseResult parse(Long paperId, File file, String documentHash);
}
