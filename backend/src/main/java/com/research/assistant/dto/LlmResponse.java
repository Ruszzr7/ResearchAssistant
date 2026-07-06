package com.research.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 调用响应 —— 包含生成内容与 token 消耗。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private String content;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    public static LlmResponse of(String content) {
        return new LlmResponse(content, 0, 0, 0);
    }
}
