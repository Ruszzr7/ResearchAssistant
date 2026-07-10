package com.research.assistant.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 写作项目详情 DTO。
 */
public class WritingProjectDto {

    private Long id;
    private String title;
    private String topic;
    private String outlineJson;
    private String relatedWork;
    private String draftContent;
    private List<Long> paperIds = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getOutlineJson() { return outlineJson; }
    public void setOutlineJson(String outlineJson) { this.outlineJson = outlineJson; }

    public String getRelatedWork() { return relatedWork; }
    public void setRelatedWork(String relatedWork) { this.relatedWork = relatedWork; }

    public String getDraftContent() { return draftContent; }
    public void setDraftContent(String draftContent) { this.draftContent = draftContent; }

    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
