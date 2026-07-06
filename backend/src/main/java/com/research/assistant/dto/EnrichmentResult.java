package com.research.assistant.dto;

/**
 * PDF 元数据自动补全结果。
 */
public class EnrichmentResult {

    private boolean found;
    private String foundDoi;
    private String foundArxivId;

    private String title;
    private String authors;
    private Integer year;
    private String source;
    private String doi;
    private String arxivId;
    private String sourceUrl;
    private String abstractText;
    private String keywords;
    private String message;

    public EnrichmentResult() {
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public String getFoundDoi() {
        return foundDoi;
    }

    public void setFoundDoi(String foundDoi) {
        this.foundDoi = foundDoi;
    }

    public String getFoundArxivId() {
        return foundArxivId;
    }

    public void setFoundArxivId(String foundArxivId) {
        this.foundArxivId = foundArxivId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthors() {
        return authors;
    }

    public void setAuthors(String authors) {
        this.authors = authors;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    public String getArxivId() {
        return arxivId;
    }

    public void setArxivId(String arxivId) {
        this.arxivId = arxivId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getAbstractText() {
        return abstractText;
    }

    public void setAbstractText(String abstractText) {
        this.abstractText = abstractText;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
