package com.research.assistant.dto;

/**
 * 创建/更新写作项目请求。
 */
public class WritingProjectRequest {

    private String title;
    private String topic;
    private String draftContent;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getDraftContent() { return draftContent; }
    public void setDraftContent(String draftContent) { this.draftContent = draftContent; }
}
