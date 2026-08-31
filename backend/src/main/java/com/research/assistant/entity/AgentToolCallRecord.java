package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_tool_call")
public class AgentToolCallRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String toolCallId;
    private String runId;
    private Integer ordinalNo;
    private String toolName;
    private String argumentsJson;
    private String status;
    private Boolean readOnly;
    private String idempotencyKey;
    private Integer attemptCount;
    private String actionTicketHash;
    private LocalDateTime actionTicketExpiresAt;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
