package com.research.assistant.service.agent.capability;

import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSettingsServiceTest {

    @Test
    void infersGeminiNativeFromTheUnifiedUrl() {
        SettingsService settings = configured("https://api.apilio.ai/v1beta",
                "secret", "gemini-2.5-flash");

        AiSettings resolved = new AiSettingsService(settings).resolve();

        assertThat(resolved.transport()).isEqualTo("GEMINI_NATIVE");
    }

    @Test
    void keepsCompatibleGatewaysOnTheOpenAiProtocol() {
        SettingsService settings = configured("https://gateway.example/v1",
                "secret", "vision-model");

        AiSettings resolved = new AiSettingsService(settings).resolve();

        assertThat(resolved.transport()).isEqualTo("OPENAI_COMPATIBLE");
    }

    @Test
    void usesSavedKeyOnlyWhenDraftKeyIsBlank() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("api_key")).thenReturn("saved-secret");

        AiSettings resolved = new AiSettingsService(settings).resolveDraft(
                "https://draft.example/v1", "draft-model", " ");

        assertThat(resolved.apiKey()).isEqualTo("saved-secret");
        assertThat(resolved.model()).isEqualTo("draft-model");
    }

    private SettingsService configured(String url, String key, String model) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("base_url")).thenReturn(url);
        when(settings.getValue("api_key")).thenReturn(key);
        when(settings.getValue("model")).thenReturn(model);
        return settings;
    }
}
