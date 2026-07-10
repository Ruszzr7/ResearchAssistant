package com.research.assistant.dto;

import java.util.Map;

/**
 * 创建/更新笔记请求。
 */
public class NoteRequest {

    private String title;
    private String content;
    private Integer page;
    private Map<String, Object> coordinates;
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
