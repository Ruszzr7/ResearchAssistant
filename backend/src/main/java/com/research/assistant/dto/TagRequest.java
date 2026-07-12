package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TagRequest {
    @NotBlank(message = "标签名称不能为空")
    @Size(max = 50, message = "标签名称长度不能超过 50")
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
