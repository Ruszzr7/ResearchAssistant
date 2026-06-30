package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 标签字典实体。通过 paper_tag 关联表与论文形成多对多关系。
 */
@TableName("tag")
public class Tag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;

    public Tag() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
