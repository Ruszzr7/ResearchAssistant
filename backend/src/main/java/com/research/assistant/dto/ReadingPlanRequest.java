package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class ReadingPlanRequest {
    @NotBlank(message = "计划名称不能为空")
    @Size(max = 200, message = "计划名称长度不能超过 200")
    private String name;
    @Size(max = 2000, message = "研究目标长度不能超过 2000")
    private String objective;
    @Size(max = 2000, message = "完成标准长度不能超过 2000")
    private String successCriteria;
    private LocalDate startDate;
    private LocalDate endDate;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }
    public String getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
