package com.research.assistant.service.impl;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiResponseNormalizer;
import com.research.assistant.service.observability.ResearchMetrics;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
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

import java.util.Base64;

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
        if (policy != null && policy.jsonOutput()
                && streamService.supportsNativeStructuredOutput()) {
            return invokeNativeStructured(
                    systemPrompt, userMessage, null, null, policy);
        }
        return invoke(systemPrompt, UserMessage.from(userMessage), policy, "chat");
    }

    @Override
    public LlmResponse chatWithImageUsage(String systemPrompt,
                                          String userMessage,
                                          byte[] imageBytes,
                                          String mimeType,
                                          LlmCallPolicy policy) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must not be empty");
        }
        if (imageBytes.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("image exceeds the multimodal request limit");
        }
        AiProviderProfile profile = modelFactory.currentProfile();
        if (!profile.vision()) {
            throw new IllegalStateException(profile.displayName() + " 当前接入不支持图片输入");
        }
        String safeMimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        if (policy != null && policy.jsonOutput()
                && streamService.supportsNativeStructuredOutput()) {
            return invokeNativeStructured(
                    systemPrompt, userMessage, imageBytes, safeMimeType, policy);
        }
        UserMessage message = UserMessage.from(
                TextContent.from(userMessage == null ? "" : userMessage),
                ImageContent.from(Base64.getEncoder().encodeToString(imageBytes), safeMimeType));
        return invoke(systemPrompt, message, policy, "chat-image");
    }

    private LlmResponse invokeNativeStructured(String systemPrompt,
                                               String userMessage,
                                               byte[] imageBytes,
                                               String mimeType,
                                               LlmCallPolicy policy) {
        long startedAt = metrics.startTimer();
        String outcome = "success";
        try {
            LlmResponse response = streamService.chatStructuredJson(
                    systemPrompt, userMessage, imageBytes, mimeType, policy);
            metrics.addTokens("input", response.getPromptTokens() == null
                    ? 0 : response.getPromptTokens());
            metrics.addTokens("output", response.getCompletionTokens() == null
                    ? 0 : response.getCompletionTokens());
            return response;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            metrics.aiFinished(imageBytes == null ? "chat-structured" : "chat-image-structured",
                    outcome, startedAt);
        }
    }

    private LlmResponse invoke(String systemPrompt,
                               UserMessage userMessage,
                               LlmCallPolicy policy,
                               String operation) {
        long startedAt = metrics.startTimer();
        String outcome = "success";
        try {
            ChatModel model = modelFactory.createChatModel();

            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt), userMessage);
            if (policy != null) {
                requestBuilder.maxOutputTokens(policy.maxOutputTokens());
                if (policy.jsonOutput() && streamService.supportsJsonResponseFormat()) {
                    requestBuilder.responseFormat(ResponseFormat.JSON);
                }
            }
            ChatRequest request = requestBuilder.build();
            ChatResponse response = model.chat(request);

            String content = AiResponseNormalizer.finalContent(
                    modelFactory.currentProfile().provider(),
                    response.aiMessage() != null ? response.aiMessage().text() : "");
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
            metrics.aiFinished(operation, outcome, startedAt);
        }
    }

    @Override
    public StreamingResponseBody chatStream(String systemPrompt, String userMessage) {
        return out -> streamService.streamChat(out, systemPrompt, userMessage);
    }
}
