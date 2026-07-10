package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 写作项目 —— 用户论文写作工作台。
 * <p>
 * 保存研究选题、AI 生成的大纲、Related Work 段落以及用户草稿内容。
 */
@TableName("writing_project")
public class WritingProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目标题 */
    private String title;

    /** 研究选题 */
    private String topic;

    /** 大纲 JSON: [{level,title,children:[]}] */
    @TableField("outline_json")
    private String outlineJson;

    /** 生成的 Related Work 段落 */
    @TableField("related_work")
    private String relatedWork;

    /** 用户写作区草稿 */
    @TableField("draft_content")
    private String draftContent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public WritingProject() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getOutlineJson() { return outlineJson; }
    public void setOutlineJson(String outlineJson) { this.outlineJson = outlineJson; }

    public String getRelatedWork() { return relatedWork; }
    public void setRelatedWork(String relatedWork) { this.relatedWork = relatedWork; }

    public String getDraftContent() { return draftContent; }
    public void setDraftContent(String draftContent) { this.draftContent = draftContent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
