package com.research.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class ReadingPlanItemRequest {
    @NotNull(message = "论文不能为空")
    private Long paperId;
    private LocalDate deadline;
    @Min(value = 1, message = "优先级必须大于等于 1")
    @Max(value = 5, message = "优先级不能超过 5")
    private Integer priority;
    @Size(max = 30, message = "状态长度不能超过 30")
    private String status;
    @Size(max = 2000, message = "备注长度不能超过 2000")
    private String notes;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
