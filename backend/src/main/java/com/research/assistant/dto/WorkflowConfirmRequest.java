package com.research.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowConfirmRequest {
    @Size(max = 200, message = "确认候选数量不能超过 200")
    @Valid
    private List<Map<String, Object>> selected;
    private Long folderId;

    public Map<String, Object> toUserInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        if (selected != null) input.put("selected", selected);
        if (folderId != null) input.put("folderId", folderId);
        return input;
    }

    public List<Map<String, Object>> getSelected() { return selected; }
    public void setSelected(List<Map<String, Object>> selected) { this.selected = selected; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
}
