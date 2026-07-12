package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OutlineRequest {
    @NotBlank(message = "主题不能为空")
    @Size(max = 4000, message = "主题长度不能超过 4000")
    private String topic;
    @Size(max = 100, message = "写作风格长度不能超过 100")
    private String style;
    @Size(max = 50, message = "语言长度不能超过 50")
    private String language;

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}
