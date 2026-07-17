package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 阅读计划条目实体。
 */
@TableName("reading_plan_item")
public class ReadingPlanItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("plan_id")
    private Long planId;

    @TableField("paper_id")
    private Long paperId;

    private String readingQuestion;

    private String expectedOutput;

    private LocalDate deadline;

    private Integer priority;

    private String status;

    private String notes;

    private String outcome;

    private Long researchSessionId;

    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public ReadingPlanItem() {
        this.priority = 0;
        this.status = "TODO";
        this.expectedOutput = "SUMMARY";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

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

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
