package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 论文结构化分析结果 —— Agent 深度阅读的产出。
 * <p>
 * 与 Paper 一对一关联，存储章节结构、核心贡献、方法分类、发现/局限等。
 *
 * @author ResearchAssistant
 */
@TableName("paper_analysis")
public class PaperAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    /** 章节结构 JSON: [{"heading":"Introduction","start":0,"end":500},...] */
    @TableField("sections_json")
    private String sectionsJson;

    /** 核心贡献（三句话） */
    @TableField("core_contribution")
    private String coreContribution;

    /** 研究方法分类: THEORETICAL / EXPERIMENTAL / SYSTEM / SURVEY */
    @TableField("method_type")
    private String methodType;

    /** 方法概述 */
    @TableField("method_summary")
    private String methodSummary;

    /** 使用的数据集 JSON */
    @TableField("datasets_json")
    private String datasetsJson;

    /** 使用的模型/算法 JSON */
    @TableField("models_json")
    private String modelsJson;

    /** 主要发现 JSON */
    @TableField("key_findings_json")
    private String keyFindingsJson;

    /** 局限性 JSON */
    @TableField("limitations_json")
    private String limitationsJson;

    /** 表格摘要 JSON */
    @TableField("tables_summary_json")
    private String tablesSummaryJson;

    /** 图表摘要 JSON */
    @TableField("figures_summary_json")
    private String figuresSummaryJson;

    /** 原始提取文本（供后续引用，不展示给用户） */
    @TableField("raw_text")
    private String rawText;

    /** 本次分析消耗的 token 数 */
    @TableField("token_used")
    private Integer tokenUsed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // ========== getter / setter ==========

    public PaperAnalysis() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getSectionsJson() { return sectionsJson; }
    public void setSectionsJson(String sectionsJson) { this.sectionsJson = sectionsJson; }
    public String getCoreContribution() { return coreContribution; }
    public void setCoreContribution(String coreContribution) { this.coreContribution = coreContribution; }
    public String getMethodType() { return methodType; }
    public void setMethodType(String methodType) { this.methodType = methodType; }
    public String getMethodSummary() { return methodSummary; }
    public void setMethodSummary(String methodSummary) { this.methodSummary = methodSummary; }
    public String getDatasetsJson() { return datasetsJson; }
    public void setDatasetsJson(String datasetsJson) { this.datasetsJson = datasetsJson; }
    public String getModelsJson() { return modelsJson; }
    public void setModelsJson(String modelsJson) { this.modelsJson = modelsJson; }
    public String getKeyFindingsJson() { return keyFindingsJson; }
    public void setKeyFindingsJson(String keyFindingsJson) { this.keyFindingsJson = keyFindingsJson; }
    public String getLimitationsJson() { return limitationsJson; }
    public void setLimitationsJson(String limitationsJson) { this.limitationsJson = limitationsJson; }
    public String getTablesSummaryJson() { return tablesSummaryJson; }
    public void setTablesSummaryJson(String tablesSummaryJson) { this.tablesSummaryJson = tablesSummaryJson; }
    public String getFiguresSummaryJson() { return figuresSummaryJson; }
    public void setFiguresSummaryJson(String figuresSummaryJson) { this.figuresSummaryJson = figuresSummaryJson; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public Integer getTokenUsed() { return tokenUsed; }
    public void setTokenUsed(Integer tokenUsed) { this.tokenUsed = tokenUsed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
