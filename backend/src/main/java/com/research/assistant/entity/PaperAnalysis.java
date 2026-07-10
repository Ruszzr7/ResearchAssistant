package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 论文结构化分析结果 —— Agent 深度阅读的产出。
 * <p>
 * 与 Paper 一对一关联，存储章节结构、核心贡献、方法分类、发现/局限、可复现要素、实验设置、Benchmark 结果等。
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

    /** 可复现要素 JSON: 公式、伪代码、源码/数据集链接、评测指标等 */
    @TableField("reproducible_artifacts_json")
    private String reproducibleArtifactsJson;

    /** 实验设置 JSON: 任务定义、数据集、基线、评测指标、实现细节 */
    @TableField("experiment_setup_json")
    private String experimentSetupJson;

    /** Benchmark 结果 JSON */
    @TableField("benchmark_results_json")
    private String benchmarkResultsJson;

    /** 与用户研究主题的相关度评分（1-10），无主题时为 null */
    @TableField("relevance_score")
    private Integer relevanceScore;

    /** 相关度评分理由 */
    @TableField("relevance_reason")
    private String relevanceReason;

    /** 公式识别结果 JSON */
    @TableField("formulas_json")
    private String formulasJson;

    /** 图表提取结果 JSON */
    @TableField("figures_json")
    private String figuresJson;

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
    public String getReproducibleArtifactsJson() { return reproducibleArtifactsJson; }
    public void setReproducibleArtifactsJson(String reproducibleArtifactsJson) { this.reproducibleArtifactsJson = reproducibleArtifactsJson; }
    public String getExperimentSetupJson() { return experimentSetupJson; }
    public void setExperimentSetupJson(String experimentSetupJson) { this.experimentSetupJson = experimentSetupJson; }
    public String getBenchmarkResultsJson() { return benchmarkResultsJson; }
    public void setBenchmarkResultsJson(String benchmarkResultsJson) { this.benchmarkResultsJson = benchmarkResultsJson; }
    public Integer getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(Integer relevanceScore) { this.relevanceScore = relevanceScore; }
    public String getRelevanceReason() { return relevanceReason; }
    public void setRelevanceReason(String relevanceReason) { this.relevanceReason = relevanceReason; }
    public String getFormulasJson() { return formulasJson; }
    public void setFormulasJson(String formulasJson) { this.formulasJson = formulasJson; }
    public String getFiguresJson() { return figuresJson; }
    public void setFiguresJson(String figuresJson) { this.figuresJson = figuresJson; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public Integer getTokenUsed() { return tokenUsed; }
    public void setTokenUsed(Integer tokenUsed) { this.tokenUsed = tokenUsed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
