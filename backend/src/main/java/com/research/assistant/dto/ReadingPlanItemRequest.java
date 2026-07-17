package com.research.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class ReadingPlanItemRequest {
    private Long paperId;
    @Size(max = 2000, message = "阅读问题长度不能超过 2000")
    private String readingQuestion;
    @Size(max = 48, message = "预期产出类型长度不能超过 48")
    private String expectedOutput;
    private LocalDate deadline;
    @Min(value = 1, message = "优先级必须大于等于 1")
    @Max(value = 5, message = "优先级不能超过 5")
    private Integer priority;
    @Size(max = 30, message = "状态长度不能超过 30")
    private String status;
    @Size(max = 2000, message = "备注长度不能超过 2000")
    private String notes;
    @Size(max = 20_000, message = "阅读产出长度不能超过 20000")
    private String outcome;
    @Positive(message = "研究会话 ID 必须为正数")
    private Long researchSessionId;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getReadingQuestion() { return readingQuestion; }
    public void setReadingQuestion(String readingQuestion) { this.readingQuestion = readingQuestion; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Long getResearchSessionId() { return researchSessionId; }
    public void setResearchSessionId(Long researchSessionId) { this.researchSessionId = researchSessionId; }
}
