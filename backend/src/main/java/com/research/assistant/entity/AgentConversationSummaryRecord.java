package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_conversation_summary")
public class AgentConversationSummaryRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer revision;
    private String schemaVersion;
    private Long coveredThroughMessageId;
    private String summaryJson;
    private LocalDateTime createdAt;
}
