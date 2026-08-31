package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_attachment")
public class AgentAttachmentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String attachmentId;
    private Long turnId;
    private Long sessionId;
    private Long messageId;
    private String attachmentKind;
    private String mediaType;
    private String originalName;
    private String storagePath;
    private String contentSha256;
    private Long sizeBytes;
    private String extractionStatus;
    private String previewText;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
