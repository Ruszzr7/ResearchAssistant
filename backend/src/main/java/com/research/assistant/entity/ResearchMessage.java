package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("research_message")
public class ResearchMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String messageKey;
    private String role;
    private String content;
    private String runId;
    private String selectionAnchorJson;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
