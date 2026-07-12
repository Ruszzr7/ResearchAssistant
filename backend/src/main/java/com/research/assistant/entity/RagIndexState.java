package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 每篇论文的 RAG 当前版本指针和版本序列。 */
@TableName("rag_index_state")
public class RagIndexState {

    @TableId
    private Long paperId;
    private Integer activeVersion;
    private Integer nextVersion;
    private LocalDateTime updatedAt;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Integer getActiveVersion() { return activeVersion; }
    public void setActiveVersion(Integer activeVersion) { this.activeVersion = activeVersion; }

    public Integer getNextVersion() { return nextVersion; }
    public void setNextVersion(Integer nextVersion) { this.nextVersion = nextVersion; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
