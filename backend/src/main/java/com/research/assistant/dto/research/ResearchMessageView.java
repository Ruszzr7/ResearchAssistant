package com.research.assistant.dto.research;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResearchMessageView {
    private Long id;
    private String messageKey;
    private String role;
    private String content;
    private String runId;
    private JsonNode selectionAnchor;
    private JsonNode evidence;
    private LocalDateTime createdAt;
}
