package com.research.assistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 调用响应 —— 包含生成内容与 token 消耗。
 */
@Data
@NoArgsConstructor
public class LlmResponse {

    private String content;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String finishReason;

    public LlmResponse(String content,
                       Integer promptTokens,
                       Integer completionTokens,
                       Integer totalTokens) {
        this(content, promptTokens, completionTokens, totalTokens, null);
    }

    public LlmResponse(String content,
                       Integer promptTokens,
                       Integer completionTokens,
                       Integer totalTokens,
                       String finishReason) {
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.finishReason = finishReason;
    }

    public static LlmResponse of(String content) {
        return new LlmResponse(content, 0, 0, 0);
    }
}
