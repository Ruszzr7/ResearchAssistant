package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("rag_consistency_audit")
public class RagConsistencyAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private String provider;
    private String status;
    private Integer activeVersion;
    private Integer metadataChunkCount;
    private Integer expectedChunkCount;
    private String detailsJson;
    private LocalDateTime checkedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getActiveVersion() { return activeVersion; }
    public void setActiveVersion(Integer activeVersion) { this.activeVersion = activeVersion; }
    public Integer getMetadataChunkCount() { return metadataChunkCount; }
    public void setMetadataChunkCount(Integer metadataChunkCount) { this.metadataChunkCount = metadataChunkCount; }
    public Integer getExpectedChunkCount() { return expectedChunkCount; }
    public void setExpectedChunkCount(Integer expectedChunkCount) { this.expectedChunkCount = expectedChunkCount; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
