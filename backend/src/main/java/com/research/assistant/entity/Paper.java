package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 论文实体 —— 论文库的核心数据。
 *
 * <ul>
 *   <li>readingStatus 参考 {@link com.research.assistant.constant.ReadingStatus}</li>
 *   <li>acquisitionMethod 参考 {@link com.research.assistant.constant.AcquisitionMethod}</li>
 *   <li>authors 存 JSON 数组：[{"name":"xxx","role":"first|corresponding"}]</li>
 * </ul>
 *
 * @author ResearchAssistant
 */
@TableName("paper")
public class Paper {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** JSON 格式：[{"name":"...","role":"first|corresponding"}] */
    private String authors;

    private Integer year;

    /** 期刊 / 会议名称 */
    private String source;

    private String doi;

    /** arXiv ID，如 2301.12345 */
    @TableField("arxiv_id")
    private String arxivId;

    /** Semantic Scholar paperId */
    @TableField("semantic_scholar_id")
    private String semanticScholarId;

    /** 论文来源 URL */
    @TableField("source_url")
    private String sourceUrl;

    /** 被引次数 */
    @TableField("citation_count")
    private Integer citationCount;

    /** 数据库列名 abstract（MySQL 保留字）→ Java 字段名 abstractText */
    @TableField("abstract")
    private String abstractText;

    /** 逗号分隔 */
    private String keywords;

    private String pdfPath;

    /** {@link com.research.assistant.constant.AcquisitionMethod} */
    private String acquisitionMethod;

    private Long folderId;

    /** {@link com.research.assistant.constant.ReadingStatus} */
    private String readingStatus;

    /** 是否置顶 */
    private Boolean pinned;

    /** PDF 总页数 */
    @TableField("page_count")
    private Integer pageCount;

    /** 当前读到第几页 */
    @TableField("current_page")
    private Integer currentPage;

    /** 累计阅读时长（秒） */
    @TableField("read_seconds")
    private Integer readSeconds;

    /** 最后阅读时间 */
    @TableField("last_read_at")
    private LocalDateTime lastReadAt;

    /** Agent 生成的内容摘要（未实现） */
    private String aiSummary;

    /** Agent 异步处理状态: PENDING / PROCESSING / COMPLETED / FAILED */
    @TableField("processing_status")
    private String processingStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 关联标签列表 —— 不存数据库，由 Service 层查询填充 */
    @TableField(exist = false)
    private List<Tag> tags;

    // ========== getter / setter ==========

    public Paper() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Integer getCitationCount() { return citationCount; }
    public void setCitationCount(Integer citationCount) { this.citationCount = citationCount; }
    public String getAbstractText() { return abstractText; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
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
    public LocalDateTime getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(LocalDateTime lastReadAt) { this.lastReadAt = lastReadAt; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
