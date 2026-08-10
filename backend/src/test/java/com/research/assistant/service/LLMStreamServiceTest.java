package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.ai.provider.AiProvider;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMStreamServiceTest {

    private final LangChain4jModelFactory modelFactory =
            mock(LangChain4jModelFactory.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final LLMStreamService service = new LLMStreamService(
            modelFactory, settingsService, new ObjectMapper());

    @Test
    void usesNativeStructuredOutputForKimiCodingEndpoint() {
        when(modelFactory.currentProfile()).thenReturn(AiProviderRegistry.resolve(
                AiProvider.KIMI.settingValue(), "coding",
                "https://api.kimi.com/coding/v1", "k3"));

        assertThat(service.supportsNativeStructuredOutput()).isTrue();
        assertThat(service.supportsJsonResponseFormat()).isTrue();
    }

    @Test
    void keepsOpenAiStreamingOnLangChainPath() {
        when(modelFactory.currentProfile()).thenReturn(AiProviderRegistry.resolve(
                AiProvider.OPENAI.settingValue(), "default",
                "https://api.openai.com/v1", "gpt-5-mini"));

        assertThat(service.supportsNativeStructuredOutput()).isTrue();
        assertThat(modelFactory.currentProfile().manualStreaming()).isFalse();
    }

    @Test
    void disablesKimiThinkingForBoundedStructuredExtraction() {
        AiProviderProfile profile = AiProviderRegistry.resolve(
                AiProvider.KIMI.settingValue(), "coding",
                "https://api.kimi.com/coding/v1", "k3-256k");
        LlmCallPolicy policy = new LlmCallPolicy(
                "formula", 10_000, 4_000, 768, 1, true, "low");

        Map<String, Object> body = service.structuredRequestBody(
                profile, "k3-256k", "system", "user", new byte[]{1}, "image/png", policy);

        assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(body).doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("max_completion_tokens", 768);
    }
}
