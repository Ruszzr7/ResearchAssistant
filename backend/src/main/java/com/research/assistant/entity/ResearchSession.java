package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("research_session")
public class ResearchSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String title;
    private String sessionType;
    private Long primaryPaperId;
    private Integer lastPage;
    private String mode;
    private String outputLanguage;
    private Boolean archived;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
