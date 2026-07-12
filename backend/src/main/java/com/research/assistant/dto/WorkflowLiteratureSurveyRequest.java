package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class WorkflowLiteratureSurveyRequest {
    @NotBlank(message = "检索目标不能为空")
    @Size(max = 4000, message = "检索目标长度不能超过 4000")
    private String query;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
}
