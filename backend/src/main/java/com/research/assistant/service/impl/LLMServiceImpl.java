package com.research.assistant.service.impl;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.observability.ResearchMetrics;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private final ResearchMetrics metrics;

    @Autowired
    public LLMServiceImpl(LangChain4jModelFactory modelFactory, LLMStreamService streamService,
                          ResearchMetrics metrics) {
        this.modelFactory = modelFactory;
        this.streamService = streamService;
        this.metrics = metrics;
    }

    public LLMServiceImpl(LangChain4jModelFactory modelFactory, LLMStreamService streamService) {
        this(modelFactory, streamService, new ResearchMetrics(new SimpleMeterRegistry()));
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
        long startedAt = metrics.startTimer();
        String outcome = "success";
        try {
            ChatModel model = modelFactory.createChatModel();

            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
            if (policy != null) {
                requestBuilder.maxOutputTokens(policy.maxOutputTokens());
                if (policy.jsonOutput()) requestBuilder.responseFormat(ResponseFormat.JSON);
            }
            ChatRequest request = requestBuilder.build();
            ChatResponse response = model.chat(request);

            String content = response.aiMessage() != null ? response.aiMessage().text() : "";
            TokenUsage usage = response.tokenUsage();

            int promptTokens = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
            int completionTokens = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
            int totalTokens = usage != null && usage.totalTokenCount() != null ? usage.totalTokenCount()
                    : promptTokens + completionTokens;
            String finishReason = response.finishReason() == null ? null : response.finishReason().name();

            metrics.addTokens("input", promptTokens);
            metrics.addTokens("output", completionTokens);
            log.info("event=ai_chat_completed inputTokens={} outputTokens={} totalTokens={} finishReason={}",
                    promptTokens, completionTokens, totalTokens, finishReason);

            return new LlmResponse(content, promptTokens, completionTokens, totalTokens, finishReason);
        } catch (RuntimeException e) {
            outcome = "failure";
            log.warn("event=ai_chat_failed errorType={}", e.getClass().getSimpleName());
            throw e;
        } finally {
            metrics.aiFinished("chat", outcome, startedAt);
        }
    }

    @Override
    public StreamingResponseBody chatStream(String systemPrompt, String userMessage) {
        return out -> streamService.streamChat(out, systemPrompt, userMessage);
    }
}
