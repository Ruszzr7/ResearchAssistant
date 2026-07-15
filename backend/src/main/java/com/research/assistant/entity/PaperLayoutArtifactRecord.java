package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Persisted version of a paper layout artifact. */
@TableName("paper_layout_artifact")
public class PaperLayoutArtifactRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private String documentHash;
    private String parserVersion;
    private String status;
    private Double layoutConfidence;
    private Integer pageCount;
    private String blocksJson;
    private String provenanceJson;
    private LocalDateTime generatedAt;
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getLayoutConfidence() { return layoutConfidence; }
    public void setLayoutConfidence(Double layoutConfidence) { this.layoutConfidence = layoutConfidence; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getBlocksJson() { return blocksJson; }
    public void setBlocksJson(String blocksJson) { this.blocksJson = blocksJson; }
    public String getProvenanceJson() { return provenanceJson; }
    public void setProvenanceJson(String provenanceJson) { this.provenanceJson = provenanceJson; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
