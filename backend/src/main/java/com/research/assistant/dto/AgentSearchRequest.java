package com.research.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AgentSearchRequest {
    @NotBlank(message = "搜索关键词不能为空")
    @Size(max = 1000, message = "搜索关键词长度不能超过 1000")
    private String query;
    @Min(value = 1, message = "maxResults 必须大于 0")
    @Max(value = 100, message = "maxResults 不能超过 100")
    private Integer maxResults = 20;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
}
