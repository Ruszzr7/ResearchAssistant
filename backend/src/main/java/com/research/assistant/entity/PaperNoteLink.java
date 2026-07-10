package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 论文与笔记的链接实体。
 */
@TableName("paper_note_link")
public class PaperNoteLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    private Long noteId;

    private Integer page;

    /**
     * 归一化坐标 JSON（与 paper_annotation.coordinates_json 同结构）。
     */
    @TableField("coordinates_json")
    private String coordinatesJson;

    /** 选中的原文片段，用于反向定位 */
    @TableField("anchor_text")
    private String anchorText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public PaperNoteLink() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Long getNoteId() { return noteId; }
    public void setNoteId(Long noteId) { this.noteId = noteId; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }

    public String getCoordinatesJson() { return coordinatesJson; }
    public void setCoordinatesJson(String coordinatesJson) { this.coordinatesJson = coordinatesJson; }

    public String getAnchorText() { return anchorText; }
    public void setAnchorText(String anchorText) { this.anchorText = anchorText; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
