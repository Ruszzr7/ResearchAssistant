package com.research.assistant.service.impl;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * DeepSeek / OpenAI 兼容 API 调用实现 —— 基于 LangChain4j。
 * <p>
 * 保留 {@link LLMService} 接口作为防腐层，所有非流式调用改走 LangChain4j 的
 * {@link ChatModel}；流式响应继续委托给 {@link LLMStreamService}，
 * 避免 Phase 0 一次性改动过大。
 */
@Service
public class LLMServiceImpl implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMServiceImpl.class);

    private final LangChain4jModelFactory modelFactory;
    private final LLMStreamService streamService;

    public LLMServiceImpl(LangChain4jModelFactory modelFactory, LLMStreamService streamService) {
        this.modelFactory = modelFactory;
        this.streamService = streamService;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatWithUsage(systemPrompt, userMessage).getContent();
    }

    @Override
    public LlmResponse chatWithUsage(String systemPrompt, String userMessage) {
        return chatWithUsage(systemPrompt, userMessage, null);
    }

    @Override
    public LlmResponse chatWithUsage(String systemPrompt, String userMessage, LlmCallPolicy policy) {
        ChatModel model = modelFactory.createChatModel();

        ChatRequest.Builder requestBuilder = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        if (policy != null) {
            requestBuilder.maxOutputTokens(policy.maxOutputTokens());
        }
        ChatRequest request = requestBuilder.build();
        ChatResponse response = model.chat(request);

        String content = response.aiMessage() != null ? response.aiMessage().text() : "";
        TokenUsage usage = response.tokenUsage();

        int promptTokens = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int completionTokens = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        int totalTokens = usage != null && usage.totalTokenCount() != null ? usage.totalTokenCount()
                : promptTokens + completionTokens;

        log.info("Token 消耗 — prompt: {}, completion: {}, total: {}",
                promptTokens, completionTokens, totalTokens);

        return new LlmResponse(content, promptTokens, completionTokens, totalTokens);
    }

    @Override
    public StreamingResponseBody chatStream(String systemPrompt, String userMessage) {
        return out -> streamService.streamChat(out, systemPrompt, userMessage);
    }
}
