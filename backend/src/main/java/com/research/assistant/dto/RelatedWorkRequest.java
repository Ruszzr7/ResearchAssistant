package com.research.assistant.dto;

import java.util.List;

/**
 * Related Work 生成请求。
 */
public class RelatedWorkRequest {

    private List<Long> paperIds;
    private String topic;
    private String style;

    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
}
