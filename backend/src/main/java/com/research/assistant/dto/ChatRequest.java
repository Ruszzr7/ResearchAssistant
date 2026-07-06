package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 分析追问请求体。
 */
@Data
public class ChatRequest {

    /** 会话标识，用于多轮记忆；为空则每次独立调用 */
    private String conversationId;

    private String context;

    @NotBlank(message = "问题不能为空")
    private String question;
}
