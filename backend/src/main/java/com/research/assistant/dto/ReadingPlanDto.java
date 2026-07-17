package com.research.assistant.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 阅读计划 DTO。
 */
public class ReadingPlanDto {

    private Long id;
    private String name;
    private String objective;
    private String successCriteria;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ReadingPlanItemDto> items;
    private Integer totalItems;
    private Integer doneItems;
    private Integer inProgressItems;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<ReadingPlanItemDto> getItems() { return items; }
    public void setItems(List<ReadingPlanItemDto> items) { this.items = items; }

    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer totalItems) { this.totalItems = totalItems; }

    public Integer getDoneItems() { return doneItems; }
    public void setDoneItems(Integer doneItems) { this.doneItems = doneItems; }

    public Integer getInProgressItems() { return inProgressItems; }
    public void setInProgressItems(Integer inProgressItems) { this.inProgressItems = inProgressItems; }
}
