package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SearchExtractRequest {
    @NotBlank(message = "研究方向描述不能为空")
    @Size(max = 4000, message = "研究方向描述长度不能超过 4000")
    private String query;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
}
