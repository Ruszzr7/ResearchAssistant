package com.research.assistant.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WritingEvidenceDto {
    private Long id;
    private Long claimId;
    private Long paperId;
    private String paperTitle;
    private Long researchSessionId;
    private String researchSessionTitle;
    private String relationType;
    private Integer pageNumber;
    private String locator;
    private String quoteText;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
