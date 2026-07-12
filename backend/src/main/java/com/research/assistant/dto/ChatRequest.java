package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {
    @Size(max = 100, message = "会话 ID 长度不能超过 100")
    private String conversationId;

    @Size(max = 12000, message = "上下文长度不能超过 12000")
    private String context;

    @NotBlank(message = "问题不能为空")
    @Size(max = 8000, message = "问题长度不能超过 8000")
    private String question;
}
