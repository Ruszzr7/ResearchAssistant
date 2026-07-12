package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

public class AnnotationRequest {
    @Size(max = 50, message = "批注类型长度不能超过 50")
    private String type;
    @Min(value = 1, message = "页码必须大于等于 1")
    private Integer page;
    @Size(max = 30, message = "颜色值长度不能超过 30")
    private String color;
    @Size(max = 4000, message = "批注长度不能超过 4000")
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
