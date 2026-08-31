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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AiCapabilityServiceTest {
    @Test
    void verifiesNativeToolCallAndContinuationBeforeEnablingChatAgent() {
        AiModelCapabilityMapper mapper = mock(AiModelCapabilityMapper.class);
        AiRoleSettingsService settings = mock(AiRoleSettingsService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        when(settings.resolve(AiModelRole.CHAT)).thenReturn(role(AiModelRole.CHAT, "a".repeat(64)));
        ChatModel model = mock(ChatModel.class);
        when(factory.createAgentChatModel()).thenReturn(model);
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("probe").name("capability_echo")
                .arguments("{\"value\":\"READY\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(java.util.List.of(call))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("DONE")).build());
        doAnswer(invocation -> { AiModelCapabilityRecord value = invocation.getArgument(0); value.setId(1L); return 1; })
                .when(mapper).insert(any(AiModelCapabilityRecord.class));
        AiCapabilityService service = new AiCapabilityService(mapper, settings, factory);

        var result = service.probe(AiModelRole.CHAT);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.toolCalling()).isTrue();
        assertThat(result.continuousTools()).isTrue();
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
        verify(mapper).insert(any(AiModelCapabilityRecord.class));
    }

    @Test
    void documentProbeKeepsImageRequiredAndTreatsPdfAsOptionalCapability() {
        AiModelCapabilityMapper mapper = mock(AiModelCapabilityMapper.class);
        AiRoleSettingsService settings = mock(AiRoleSettingsService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        when(settings.resolve(AiModelRole.DOCUMENT)).thenReturn(role(AiModelRole.DOCUMENT, "b".repeat(64)));
        ChatModel model = mock(ChatModel.class);
        when(factory.createDocumentModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.data.message.ChatMessage[].class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("IMAGE_OK")).build())
                .thenThrow(new RuntimeException("pdf unsupported"));
        AiCapabilityService service = new AiCapabilityService(mapper, settings, factory);

        var result = service.probe(AiModelRole.DOCUMENT);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.image()).isTrue();
        assertThat(result.pdf()).isFalse();
    }

    private AiRoleSettings role(AiModelRole role, String signature) {
        return new AiRoleSettings(role, "OPENAI_COMPATIBLE", "test", "default", "https://example.test/v1",
                "secret", "fake", signature);
    }
}
