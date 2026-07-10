package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * PDF 批注实体。
 */
@TableName("paper_annotation")
public class PaperAnnotation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    /** HIGHLIGHT / UNDERLINE / NOTE / FREEHAND */
    private String type;

    private Integer page;

    /** 颜色，例如 #ffeb3b */
    private String color;

    /** 批注文字内容（便签或高亮附注） */
    private String note;

    /**
     * 归一化坐标 JSON。
     * <pre>
     * {
     *   "pageWidth": 612,
     *   "pageHeight": 792,
     *   "rotation": 0,
     *   "scale": 1.5,
     *   "quads": [{"x1":0.1,"y1":0.2,"x2":0.3,"y2":0.2,...}, ...]
     * }
     * </pre>
     */
    @TableField("coordinates_json")
    private String coordinatesJson;

    /**
     * 是否由 AI 自动生成。
     */
    @TableField("ai_generated")
    private Boolean aiGenerated;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public PaperAnnotation() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getCoordinatesJson() { return coordinatesJson; }
    public void setCoordinatesJson(String coordinatesJson) { this.coordinatesJson = coordinatesJson; }

    public Boolean getAiGenerated() { return aiGenerated; }
    public void setAiGenerated(Boolean aiGenerated) { this.aiGenerated = aiGenerated; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
