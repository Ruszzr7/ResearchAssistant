package com.research.assistant.dto;

import java.util.Map;

/**
 * 创建/更新 PDF 批注请求。
 */
public class AnnotationRequest {

    private String type;
    private Integer page;
    private String color;
    private String note;
    private Map<String, Object> coordinates;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Map<String, Object> getCoordinates() { return coordinates; }
    public void setCoordinates(Map<String, Object> coordinates) { this.coordinates = coordinates; }
}
