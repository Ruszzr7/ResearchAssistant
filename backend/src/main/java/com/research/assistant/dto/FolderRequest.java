package com.research.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class FolderRequest {
    @NotBlank(message = "文件夹名称不能为空")
    @Size(max = 100, message = "文件夹名称长度不能超过 100")
    private String name;
    private Long parentId;
    @Min(value = 0, message = "排序值不能为负数")
    @Max(value = 1_000_000, message = "排序值过大")
    private Integer sortOrder;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
