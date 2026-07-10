package com.research.assistant.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 笔记 DTO。
 */
public class NoteDto {

    private Long id;
    private String title;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 当从某篇论文查询笔记时，携带该论文与此笔记的链接信息 */
    private Long linkId;
    private Integer page;
    private Map<String, Object> coordinates;
    private String anchorText;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getLinkId() { return linkId; }
    public void setLinkId(Long linkId) { this.linkId = linkId; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }

    public Map<String, Object> getCoordinates() { return coordinates; }
    public void setCoordinates(Map<String, Object> coordinates) { this.coordinates = coordinates; }

    public String getAnchorText() { return anchorText; }
    public void setAnchorText(String anchorText) { this.anchorText = anchorText; }
}
