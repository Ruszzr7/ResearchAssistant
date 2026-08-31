package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_turn")
public class AgentTurnRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String turnId;
    private Long sessionId;
    private String clientRequestId;
    private Long sequenceNo;
    private String status;
    private String initialMessageKey;
    private String finalMessageKey;
    private String errorCode;
    private String errorMessage;
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
