package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class RelatedWorkRequest {
    @NotEmpty(message = "至少需要一篇论文")
    @Size(max = 50, message = "论文数量不能超过 50")
    private List<Long> paperIds;
    @Size(max = 4000, message = "主题长度不能超过 4000")
    private String topic;
    @Size(max = 100, message = "写作风格长度不能超过 100")
    private String style;

    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
}
