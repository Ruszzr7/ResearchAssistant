package com.research.assistant.dto.research;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ResearchSessionSummary {
    private Long id;
    private String title;
    private String sessionType;
    private Long primaryPaperId;
    private Integer lastPage;
    private String mode;
    private String outputLanguage;
    private Boolean archived;
    private long messageCount;
    private long runCount;
    private List<ResearchPaperView> papers;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
