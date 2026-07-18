package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WritingEvidenceRequest {
    @NotNull(message = "论文不能为空")
    @Positive(message = "论文 ID 不合法")
    private Long paperId;

    @NotBlank(message = "证据关系不能为空")
    private String relationType;

    @Min(value = 1, message = "页码必须大于 0")
    private Integer pageNumber;

    @Size(max = 255, message = "定位信息长度不能超过 255")
    private String locator;

    @NotBlank(message = "证据原文不能为空")
    @Size(max = 20000, message = "证据原文长度不能超过 20000")
    private String quoteText;

    @Size(max = 1000, message = "证据说明长度不能超过 1000")
    private String note;

    @Positive(message = "研究会话 ID 不合法")
    private Long researchSessionId;
}
