package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * RAG 文档分片持久化实体。
 */
@TableName("paper_chunk")
public class PaperChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;

    /** 所属 RAG 索引版本；历史数据迁移为 1。 */
    private Integer indexVersion;

    private String chunkType;

    private String content;

    /** embedding float 数组的 JSON 字符串 */
    private String embeddingJson;

    private String source;

    private LocalDateTime createdAt;

    public PaperChunk() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public Integer getIndexVersion() { return indexVersion; }
    public void setIndexVersion(Integer indexVersion) { this.indexVersion = indexVersion; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
