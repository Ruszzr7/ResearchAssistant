package com.research.assistant.dto.research;

import lombok.Data;

@Data
public class ResearchPaperView {
    private Long id;
    private String title;
    private Integer year;
    private String source;
    private Integer positionNo;
}
