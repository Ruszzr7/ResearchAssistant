package com.research.assistant.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class WritingClaimDto {
    private Long id;
    private Long projectId;
    private String sectionName;
    private String claimText;
    private Integer positionNo;
    private String evidenceState;
    private int supportCount;
    private int contradictionCount;
    private int contextCount;
    private List<WritingEvidenceDto> evidence = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
