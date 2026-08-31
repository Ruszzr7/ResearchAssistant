package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model_capability")
public class AiModelCapabilityRecord {
    @TableId(type = IdType.AUTO) private Long id;
    private String modelRole;
    private String configSignature;
    private String status;
    private Boolean chatSupported;
    private Boolean toolCallingSupported;
    private Boolean continuousToolsSupported;
    private Boolean structuredSupported;
    private Boolean imageSupported;
    private Boolean pdfSupported;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime verifiedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
