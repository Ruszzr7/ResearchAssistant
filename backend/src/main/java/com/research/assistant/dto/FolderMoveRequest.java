package com.research.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class FolderMoveRequest {
    private Long parentId;
    @Min(value = 0, message = "排序值不能为负数")
    @Max(value = 1_000_000, message = "排序值过大")
    private Integer sortOrder = 0;

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
