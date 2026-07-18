package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WritingClaimRequest {
    @NotBlank(message = "所属章节不能为空")
    @Size(max = 120, message = "所属章节长度不能超过 120")
    private String sectionName;

    @NotBlank(message = "论点不能为空")
    @Size(max = 8000, message = "论点长度不能超过 8000")
    private String claimText;
}
