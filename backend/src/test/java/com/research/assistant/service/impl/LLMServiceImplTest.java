package com.research.assistant.service.impl;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link LLMServiceImpl} 单元测试。
 */
class LLMServiceImplTest {

    private final LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
    private final LLMStreamService streamService = mock(LLMStreamService.class);
    private final LLMServiceImpl llmService = new LLMServiceImpl(modelFactory, streamService);

    @Test
    void chatShouldReturnContent() {
        givenChatModelReturns("OK", 5, 3);

        String result = llmService.chat("system", "ping");

        assertThat(result).isEqualTo("OK");
    }

    @Test
    void chatWithUsageShouldReturnTokenCounts() {
        givenChatModelReturns("分析结果", 10, 5);

        LlmResponse response = llmService.chatWithUsage("system", "user");

        assertThat(response.getContent()).isEqualTo("分析结果");
        assertThat(response.getPromptTokens()).isEqualTo(10);
        assertThat(response.getCompletionTokens()).isEqualTo(5);
        assertThat(response.getTotalTokens()).isEqualTo(15);
    }

    @Test
    void chatWithUsageShouldHandleNullTokenUsage() {
        givenChatModelReturns("OK", null, null);

        LlmResponse response = llmService.chatWithUsage("system", "user");

        assertThat(response.getContent()).isEqualTo("OK");
        assertThat(response.getTotalTokens()).isEqualTo(0);
    }

    private void givenChatModelReturns(String content, Integer inputTokens, Integer outputTokens) {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(content))
                        .tokenUsage(new TokenUsage(inputTokens, outputTokens))
                        .build();
            }
        };
        when(modelFactory.createChatModel()).thenReturn(chatModel);
    }
}
