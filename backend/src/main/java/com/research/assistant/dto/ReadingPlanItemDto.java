package com.research.assistant.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 阅读计划条目 DTO。
 */
public class ReadingPlanItemDto {

    private Long id;
    private Long planId;
    private Long paperId;
    private String paperTitle;
    private String planName;
    private String readingQuestion;
    private String expectedOutput;
    private LocalDate deadline;
    private Integer priority;
    private String status;
    private String notes;
    private String outcome;
    private Long researchSessionId;
    private LocalDateTime completedAt;
    private List<String> paperTags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }

    public String getPaperTitle() { return paperTitle; }
    public void setPaperTitle(String paperTitle) { this.paperTitle = paperTitle; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
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
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public List<String> getPaperTags() { return paperTags; }
    public void setPaperTags(List<String> paperTags) { this.paperTags = paperTags; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
