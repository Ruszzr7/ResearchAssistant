package com.research.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Client-owned paper metadata. Server-managed fields are intentionally ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaperWriteRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 500, message = "标题长度不能超过 500")
    private String title;
    @Size(max = 20_000, message = "作者信息长度不能超过 20000")
    private String authors;
    @Min(value = 1900, message = "年份无效")
    @Max(value = 3000, message = "年份无效")
    private Integer year;
    @Size(max = 500, message = "来源长度不能超过 500")
    private String source;
    @Size(max = 500, message = "DOI 长度不能超过 500")
    private String doi;
    @Size(max = 100, message = "arXiv ID 长度不能超过 100")
    private String arxivId;
    @Size(max = 200, message = "Semantic Scholar ID 长度不能超过 200")
    private String semanticScholarId;
    @Size(max = 2_000, message = "来源链接长度不能超过 2000")
    private String sourceUrl;
    @Size(max = 50_000, message = "摘要长度不能超过 50000")
    private String abstractText;
    @Size(max = 4_000, message = "关键词长度不能超过 4000")
    private String keywords;
    @Size(max = 50, message = "获取方式长度不能超过 50")
    private String acquisitionMethod;
    private Long folderId;
    @Size(max = 50, message = "阅读状态长度不能超过 50")
    private String readingStatus;
    private Boolean pinned;
    @Min(value = 0, message = "页数不能为负数")
    @Max(value = 2_000_000, message = "页数过大")
    private Integer pageCount;
    @Min(value = 0, message = "当前页不能为负数")
    @Max(value = 2_000_000, message = "当前页过大")
    private Integer currentPage;
    @Min(value = 0, message = "阅读时长不能为负数")
    @Max(value = 2_000_000_000, message = "阅读时长过大")
    private Integer readSeconds;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthors() { return authors; }
    public void setAuthors(String authors) { this.authors = authors; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDoi() { return doi; }
    public void setDoi(String doi) { this.doi = doi; }
    public String getArxivId() { return arxivId; }
    public void setArxivId(String arxivId) { this.arxivId = arxivId; }
    public String getSemanticScholarId() { return semanticScholarId; }
    public void setSemanticScholarId(String semanticScholarId) { this.semanticScholarId = semanticScholarId; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getAbstractText() { return abstractText; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getAcquisitionMethod() { return acquisitionMethod; }
    public void setAcquisitionMethod(String acquisitionMethod) { this.acquisitionMethod = acquisitionMethod; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
    public String getReadingStatus() { return readingStatus; }
    public void setReadingStatus(String readingStatus) { this.readingStatus = readingStatus; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getCurrentPage() { return currentPage; }
    public void setCurrentPage(Integer currentPage) { this.currentPage = currentPage; }
    public Integer getReadSeconds() { return readSeconds; }
    public void setReadSeconds(Integer readSeconds) { this.readSeconds = readSeconds; }
}
