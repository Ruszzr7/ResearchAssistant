package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Persisted recognition/correction state for one version-bound PDF formula region. */
@TableName("paper_formula_region")
public class PaperFormulaRegionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private String documentHash;
    private String parserVersion;
    private Integer pageNumber;
    private String regionKey;
    private Double boxX;
    private Double boxY;
    private Double boxWidth;
    private Double boxHeight;
    private String latex;
    private String formulaItemsJson;
    private Double confidence;
    private String source;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String documentHash) { this.documentHash = documentHash; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getRegionKey() { return regionKey; }
    public void setRegionKey(String regionKey) { this.regionKey = regionKey; }
    public Double getBoxX() { return boxX; }
    public void setBoxX(Double boxX) { this.boxX = boxX; }
    public Double getBoxY() { return boxY; }
    public void setBoxY(Double boxY) { this.boxY = boxY; }
    public Double getBoxWidth() { return boxWidth; }
    public void setBoxWidth(Double boxWidth) { this.boxWidth = boxWidth; }
    public Double getBoxHeight() { return boxHeight; }
    public void setBoxHeight(Double boxHeight) { this.boxHeight = boxHeight; }
    public String getLatex() { return latex; }
    public void setLatex(String latex) { this.latex = latex; }
    public String getFormulaItemsJson() { return formulaItemsJson; }
    public void setFormulaItemsJson(String formulaItemsJson) { this.formulaItemsJson = formulaItemsJson; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
