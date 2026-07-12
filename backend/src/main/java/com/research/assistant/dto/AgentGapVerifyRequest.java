package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AgentGapVerifyRequest {
    @NotBlank(message = "Gap 分析结果不能为空")
    @Size(max = 50000, message = "Gap 分析结果长度不能超过 50000")
    private String gaps;

    public String getGaps() { return gaps; }
    public void setGaps(String gaps) { this.gaps = gaps; }
}
