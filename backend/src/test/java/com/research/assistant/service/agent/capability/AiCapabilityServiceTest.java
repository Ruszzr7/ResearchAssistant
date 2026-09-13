package com.research.assistant.service.agent.capability;

import com.research.assistant.entity.AiModelCapabilityRecord;
import com.research.assistant.mapper.AiModelCapabilityMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCapabilityServiceTest {

    @Test
    void verifiesToolImageContinuationAndStructuredOutputTogether() {
        AiModelCapabilityMapper mapper = mock(AiModelCapabilityMapper.class);
        AiSettingsService settings = mock(AiSettingsService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        when(settings.resolve()).thenReturn(settings("a".repeat(64)));
        ChatModel model = mock(ChatModel.class);
        when(factory.createAgentChatModel()).thenReturn(model);
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("probe").name("capability_echo")
                .arguments("{\"value\":\"READY\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(java.util.List.of(call))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"tool\":\"READY\",\"image\":\"VISION_7\"}")).build())
                .thenThrow(new RuntimeException("pdf unsupported"));
        doAnswer(invocation -> {
            AiModelCapabilityRecord value = invocation.getArgument(0);
            value.setId(1L);
            return 1;
        }).when(mapper).insert(any(AiModelCapabilityRecord.class));
        AiCapabilityService service = new AiCapabilityService(mapper, settings, factory);

        var result = service.probe();

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.toolCalling()).isTrue();
        assertThat(result.continuousTools()).isTrue();
        assertThat(result.toolImageContinuation()).isTrue();
        assertThat(result.image()).isTrue();
        assertThat(result.structured()).isTrue();
        assertThat(result.pdf()).isFalse();
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(3)).chat(requests.capture());
        assertThat(requests.getAllValues().get(0).toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(requests.getAllValues().get(1).messages().toString()).contains("ImageContent");
        verify(mapper).insert(any(AiModelCapabilityRecord.class));
    }

    private AiSettings settings(String signature) {
        return new AiSettings("OPENAI_COMPATIBLE", "test", "default",
                "https://example.test/v1", "secret", "fake", signature);
    }
}
