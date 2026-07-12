package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class PaperBatchMoveRequest {
    @NotEmpty(message = "至少选择一篇论文")
    @Size(max = 500, message = "一次最多移动 500 篇论文")
    private List<@NotNull Long> ids;
    private Long folderId;

    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
}
