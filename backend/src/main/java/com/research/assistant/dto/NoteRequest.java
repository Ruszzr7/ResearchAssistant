package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

public class NoteRequest {
    @Size(max = 200, message = "笔记标题长度不能超过 200")
    private String title;
    @Size(max = 20000, message = "笔记内容长度不能超过 20000")
    private String content;
    @Min(value = 1, message = "页码必须大于等于 1")
    private Integer page;
    private Map<String, Object> coordinates;
    @Size(max = 1000, message = "锚文本长度不能超过 1000")
    private String anchorText;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Map<String, Object> getCoordinates() { return coordinates; }
    public void setCoordinates(Map<String, Object> coordinates) { this.coordinates = coordinates; }
    public String getAnchorText() { return anchorText; }
    public void setAnchorText(String anchorText) { this.anchorText = anchorText; }
}
