package com.research.assistant.service.impl;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
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

    @Test
    void policyShouldSetRequestOutputTokenLimit() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertThat(request.maxOutputTokens()).isEqualTo(123);
                return ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("OK"))
                        .tokenUsage(new TokenUsage(1, 2))
                        .build();
            }
        };
        when(modelFactory.createChatModel()).thenReturn(chatModel);

        LlmResponse response = llmService.chatWithUsage(
                "system", "user", new LlmCallPolicy("test", 100, 100, 123, 1));

        assertThat(response.getTotalTokens()).isEqualTo(3);
    }

    @Test
    void jsonPolicyShouldRequestProviderJsonMode() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertThat(request.responseFormat()).isEqualTo(ResponseFormat.JSON);
                return ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("{}"))
                        .tokenUsage(new TokenUsage(1, 2))
                        .build();
            }
        };
        when(modelFactory.createChatModel()).thenReturn(chatModel);

        LlmResponse response = llmService.chatWithUsage(
                "system", "user", new LlmCallPolicy("test-json", 100, 100, 123, 1, true));

        assertThat(response.getContent()).isEqualTo("{}");
    }

    @Test
    void imageChatShouldSendTextAndBase64ImageWithoutChangingUsageAccounting() {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertThat(request.messages()).hasSize(2);
                assertThat(request.messages().get(0)).isInstanceOf(SystemMessage.class);
                UserMessage user = (UserMessage) request.messages().get(1);
                assertThat(user.contents()).hasSize(2);
                assertThat(user.contents().get(0)).isInstanceOf(TextContent.class);
                assertThat(user.contents().get(1)).isInstanceOf(ImageContent.class);
                return ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("{\"latex\":\"x\"}"))
                        .tokenUsage(new TokenUsage(7, 2))
                        .build();
            }
        };
        when(modelFactory.createChatModel()).thenReturn(chatModel);

        LlmResponse response = llmService.chatWithImageUsage(
                "system", "transcribe", new byte[]{1, 2, 3}, "image/png",
                new LlmCallPolicy("image-test", 100, 100, 123, 1, true));

        assertThat(response.getTotalTokens()).isEqualTo(9);
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
