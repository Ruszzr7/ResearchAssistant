package com.research.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class PaperTagIdsRequest {
    @NotNull(message = "tagIds 不能为空")
    @Size(max = 100, message = "一次最多设置 100 个标签")
    private List<@NotNull @Valid Long> tagIds;

    public List<Long> getTagIds() { return tagIds; }
    public void setTagIds(List<Long> tagIds) { this.tagIds = tagIds; }
}
