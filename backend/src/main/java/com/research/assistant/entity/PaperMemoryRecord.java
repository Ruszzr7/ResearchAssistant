package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String chunkSummariesJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String profileJson;
    private String memoryQualityJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String profileQualityJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String understandingVersion;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String stageText;
    private Integer revision;
    private Integer totalChunks;
    private Integer completedChunks;
    private Integer failedChunks;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer understandingAttemptCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime understandingStartedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime understandingCompletedAt;
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
    public String getProfileQualityJson() { return profileQualityJson; }
    public void setProfileQualityJson(String profileQualityJson) { this.profileQualityJson = profileQualityJson; }
    public String getUnderstandingVersion() { return understandingVersion; }
    public void setUnderstandingVersion(String understandingVersion) { this.understandingVersion = understandingVersion; }
    public String getStageText() { return stageText; }
    public void setStageText(String stageText) { this.stageText = stageText; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public Integer getCompletedChunks() { return completedChunks; }
    public void setCompletedChunks(Integer completedChunks) { this.completedChunks = completedChunks; }
    public Integer getFailedChunks() { return failedChunks; }
    public void setFailedChunks(Integer failedChunks) { this.failedChunks = failedChunks; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getUnderstandingAttemptCount() { return understandingAttemptCount; }
    public void setUnderstandingAttemptCount(Integer understandingAttemptCount) { this.understandingAttemptCount = understandingAttemptCount; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public LocalDateTime getUnderstandingStartedAt() { return understandingStartedAt; }
    public void setUnderstandingStartedAt(LocalDateTime understandingStartedAt) { this.understandingStartedAt = understandingStartedAt; }
    public LocalDateTime getUnderstandingCompletedAt() { return understandingCompletedAt; }
    public void setUnderstandingCompletedAt(LocalDateTime understandingCompletedAt) { this.understandingCompletedAt = understandingCompletedAt; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
