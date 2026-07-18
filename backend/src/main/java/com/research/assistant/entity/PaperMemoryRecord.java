package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Persisted, versioned paper memory. Large semantic fields remain JSON documents. */
@TableName("paper_memory")
public class PaperMemoryRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private String documentHash;
    private String layoutParserVersion;
    private String schemaVersion;
    private String status;
    private String structureJson;
    private String chunkSummariesJson;
    private String profileJson;
    private String memoryQualityJson;
    private Integer revision;
    private String lastErrorCode;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String documentHash) { this.documentHash = documentHash; }
    public String getLayoutParserVersion() { return layoutParserVersion; }
    public void setLayoutParserVersion(String layoutParserVersion) { this.layoutParserVersion = layoutParserVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStructureJson() { return structureJson; }
    public void setStructureJson(String structureJson) { this.structureJson = structureJson; }
    public String getChunkSummariesJson() { return chunkSummariesJson; }
    public void setChunkSummariesJson(String chunkSummariesJson) { this.chunkSummariesJson = chunkSummariesJson; }
    public String getProfileJson() { return profileJson; }
    public void setProfileJson(String profileJson) { this.profileJson = profileJson; }
    public String getMemoryQualityJson() { return memoryQualityJson; }
    public void setMemoryQualityJson(String memoryQualityJson) { this.memoryQualityJson = memoryQualityJson; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
