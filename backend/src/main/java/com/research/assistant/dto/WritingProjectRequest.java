package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class WritingProjectRequest {
    @NotBlank(message = "项目标题不能为空")
    @Size(max = 200, message = "项目标题长度不能超过 200")
    private String title;
    @Size(max = 4000, message = "主题长度不能超过 4000")
    private String topic;
    @Size(max = 100000, message = "草稿长度不能超过 100000")
    private String draftContent;
    @Size(max = 100000, message = "大纲长度不能超过 100000")
    private String outlineJson;
    @Size(max = 100000, message = "相关工作长度不能超过 100000")
    private String relatedWork;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getDraftContent() { return draftContent; }
    public void setDraftContent(String draftContent) { this.draftContent = draftContent; }
    public String getOutlineJson() { return outlineJson; }
    public void setOutlineJson(String outlineJson) { this.outlineJson = outlineJson; }
    public String getRelatedWork() { return relatedWork; }
    public void setRelatedWork(String relatedWork) { this.relatedWork = relatedWork; }
}
