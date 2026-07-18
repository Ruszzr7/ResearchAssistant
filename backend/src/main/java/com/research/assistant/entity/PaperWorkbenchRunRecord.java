package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("paper_workbench_run")
public class PaperWorkbenchRunRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String taskId;
    private Long researchSessionId;
    private Long primaryPaperId;
    private String conversationId;
    private String workflow;
    private String scope;
    private String status;
    private String paperIdsJson;
    private String requestJson;
    private String planJson;
    private String artifactVersionsJson;
    private String contextSchemaVersion;
    private String contextSnapshotJson;
    private Boolean evidenceRequired;
    private Integer maxSteps;
    private Integer tokenBudget;
    private Integer repairCount;
    private Integer evidenceCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getResearchSessionId() { return researchSessionId; }
    public void setResearchSessionId(Long researchSessionId) { this.researchSessionId = researchSessionId; }
    public Long getPrimaryPaperId() { return primaryPaperId; }
    public void setPrimaryPaperId(Long primaryPaperId) { this.primaryPaperId = primaryPaperId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getWorkflow() { return workflow; }
    public void setWorkflow(String workflow) { this.workflow = workflow; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaperIdsJson() { return paperIdsJson; }
    public void setPaperIdsJson(String paperIdsJson) { this.paperIdsJson = paperIdsJson; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public String getArtifactVersionsJson() { return artifactVersionsJson; }
    public void setArtifactVersionsJson(String artifactVersionsJson) { this.artifactVersionsJson = artifactVersionsJson; }
    public String getContextSchemaVersion() { return contextSchemaVersion; }
    public void setContextSchemaVersion(String contextSchemaVersion) { this.contextSchemaVersion = contextSchemaVersion; }
    public String getContextSnapshotJson() { return contextSnapshotJson; }
    public void setContextSnapshotJson(String contextSnapshotJson) { this.contextSnapshotJson = contextSnapshotJson; }
    public Boolean getEvidenceRequired() { return evidenceRequired; }
    public void setEvidenceRequired(Boolean evidenceRequired) { this.evidenceRequired = evidenceRequired; }
    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }
    public Integer getTokenBudget() { return tokenBudget; }
    public void setTokenBudget(Integer tokenBudget) { this.tokenBudget = tokenBudget; }
    public Integer getRepairCount() { return repairCount; }
    public void setRepairCount(Integer repairCount) { this.repairCount = repairCount; }
    public Integer getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(Integer evidenceCount) { this.evidenceCount = evidenceCount; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
