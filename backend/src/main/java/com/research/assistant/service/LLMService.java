package com.research.assistant.service;

import com.research.assistant.dto.LlmResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * LLM 调用服务 —— 封装 DeepSeek API，所有 AI 调用的单一入口。
 * <p>
 * 每次调用从 settings 表读取最新 API Key，支持热更新。
 */
public interface LLMService {

    /**
     * 发送一次对话请求，仅返回文本内容。
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * 发送一次对话请求，返回内容与 token 消耗。
     */
    LlmResponse chatWithUsage(String systemPrompt, String userMessage);

    /**
     * 流式对话 —— 通过 SSE 逐 token 返回。
     */
    StreamingResponseBody chatStream(String systemPrompt, String userMessage);
}
