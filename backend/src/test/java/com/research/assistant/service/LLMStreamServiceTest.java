package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import org.junit.jupiter.api.Test;

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
        when(settingsService.getValue("model")).thenReturn("kimi-k2.7-code");
        when(settingsService.getValue("base_url"))
                .thenReturn("https://api.kimi.com/coding/v1");

        assertThat(service.supportsNativeStructuredOutput()).isTrue();
    }

    @Test
    void keepsGenericProvidersOnLangChainPath() {
        when(settingsService.getValue("model")).thenReturn("other-code-model");
        when(settingsService.getValue("base_url"))
                .thenReturn("https://example.test/v1");

        assertThat(service.supportsNativeStructuredOutput()).isFalse();
    }
}
