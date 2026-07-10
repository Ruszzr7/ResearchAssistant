package com.research.assistant.dto;

import java.time.LocalDate;

/**
 * 创建/更新阅读计划条目请求。
 */
public class ReadingPlanItemRequest {

    private Long paperId;
    private LocalDate deadline;
    private Integer priority;
    private String status;
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
