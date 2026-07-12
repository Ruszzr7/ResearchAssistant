package com.research.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchImportPaper {
    @NotBlank(message = "论文标题不能为空")
    @Size(max = 1_000, message = "论文标题长度不能超过 1000")
    private String title;
    @Size(max = 10_000, message = "作者信息长度不能超过 10000")
    private String authors;
    @Size(max = 50_000, message = "摘要长度不能超过 50000")
    private String summary;
    @Size(max = 50, message = "发表时间长度不能超过 50")
    private String published;
    @Size(max = 100, message = "arXiv ID 长度不能超过 100")
    private String arxivId;
    @Size(max = 300, message = "外部 ID 长度不能超过 300")
    private String externalId;
    @Size(max = 200, message = "来源长度不能超过 200")
    private String source;
    @Size(max = 2_000, message = "来源链接长度不能超过 2000")
    private String sourceUrl;
    @Size(max = 2_000, message = "PDF 链接长度不能超过 2000")
    private String pdfUrl;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthors() { return authors; }
    public void setAuthors(String authors) { this.authors = authors; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPublished() { return published; }
    public void setPublished(String published) { this.published = published; }
    public String getArxivId() { return arxivId; }
    public void setArxivId(String arxivId) { this.arxivId = arxivId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getPdfUrl() { return pdfUrl; }
    public void setPdfUrl(String pdfUrl) { this.pdfUrl = pdfUrl; }
}
