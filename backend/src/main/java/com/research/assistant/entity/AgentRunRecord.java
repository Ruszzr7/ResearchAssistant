package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRunRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private Long turnId;
    private Integer attemptNo;
    private String status;
    private String modelConfigVersion;
    private String modelCapabilitySignature;
    private String modelSnapshotJson;
    private String contextSchemaVersion;
    private String contextSnapshotJson;
    private String documentHash;
    private String parserVersion;
    private Integer maxModelCalls;
    private Integer maxToolCalls;
    private Integer tokenBudget;
    private Long timeoutMs;
    private Integer modelCalls;
    private Integer toolCalls;
    private Integer promptTokens;
    private Integer completionTokens;
    private String modelTraceJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
