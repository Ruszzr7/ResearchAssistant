package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 分析追问请求体。
 */
@Data
public class ChatRequest {

    private String context;

    @NotBlank(message = "问题不能为空")
    private String question;
}
