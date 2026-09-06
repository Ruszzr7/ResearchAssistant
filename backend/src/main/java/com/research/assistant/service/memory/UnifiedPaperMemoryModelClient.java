package com.research.assistant.service.memory;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnifiedPaperMemoryModelClient implements PaperMemoryModelClient {
    private final LangChain4jModelFactory modelFactory;
    private final AiCapabilityService capabilityService;

    public UnifiedPaperMemoryModelClient(LangChain4jModelFactory modelFactory,
                                         AiCapabilityService capabilityService) {
        this.modelFactory = modelFactory;
        this.capabilityService = capabilityService;
    }

    @Override
    public LlmResponse chat(String systemPrompt, List<Content> userContents, LlmCallPolicy policy) {
        capabilityService.requireReady();
        var response = modelFactory.createPaperUnderstandingModel().chat(ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(
                        userContents == null ? List.of() : userContents))
                .maxOutputTokens(policy.maxOutputTokens()).build());
        var usage = response.tokenUsage();
        return new LlmResponse(response.aiMessage().text(),
                usage == null ? 0 : usage.inputTokenCount(), usage == null ? 0 : usage.outputTokenCount(),
                usage == null ? 0 : usage.totalTokenCount(),
                response.finishReason() == null ? null : response.finishReason().name());
    }
}
