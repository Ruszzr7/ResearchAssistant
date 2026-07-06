package com.research.assistant.service.identifier;

/**
 * 从 PDF 文本中识别出的学术标识符。
 */
public class IdentifierResult {

    private final String doi;
    private final String arxivId;

    public IdentifierResult(String doi, String arxivId) {
        this.doi = doi;
        this.arxivId = arxivId;
    }

    /** 识别出的 DOI，未识别时为 null */
    public String getDoi() {
        return doi;
    }

    /** 识别出的 arXiv ID（已去版本号），未识别时为 null */
    public String getArxivId() {
        return arxivId;
    }

    /**
     * 是否识别到任一可用标识符。
     */
    public boolean isFound() {
        return doi != null || arxivId != null;
    }
}
