package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AgentPlanRequest {
    @NotBlank(message = "目标不能为空")
    @Size(max = 8000, message = "目标长度不能超过 8000")
    private String goal;

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
}
