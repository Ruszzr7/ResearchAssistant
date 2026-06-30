package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件夹实体 —— 支持嵌套（parentId 指向父文件夹，null = 根目录）。
 */
@TableName("folder")
public class Folder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long parentId;      // null = 根文件夹
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(exist = false)   // 该文件夹及其子文件夹下的论文总数
    private Integer paperCount;

    @TableField(exist = false)   // 仅返回前端用，不存数据库
    private List<Folder> children;

    public Folder() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getPaperCount() { return paperCount; }
    public void setPaperCount(Integer paperCount) { this.paperCount = paperCount; }
    public List<Folder> getChildren() { return children; }
    public void setChildren(List<Folder> children) { this.children = children; }
}
