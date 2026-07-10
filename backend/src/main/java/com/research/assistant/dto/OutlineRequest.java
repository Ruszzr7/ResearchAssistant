package com.research.assistant.dto;

/**
 * 大纲生成请求。
 */
public class OutlineRequest {

    private String topic;
    private String style;
    private String language;

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}
