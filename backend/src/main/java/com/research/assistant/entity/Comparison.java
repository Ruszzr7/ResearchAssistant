package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 论文对比记录 —— 存储多次对比的结果。
 *
 * @author ResearchAssistant
 */
@TableName("comparison")
public class Comparison {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逗号分隔的论文 ID，如 "1,3,7" */
    @TableField("paper_ids")
    private String paperIds;

    /** 对比结果 JSON —— Agent 输出 */
    @TableField("result_json")
    private String resultJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public Comparison() {}

    // ========== getter / setter ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPaperIds() { return paperIds; }
    public void setPaperIds(String paperIds) { this.paperIds = paperIds; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
