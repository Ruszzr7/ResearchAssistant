package com.research.assistant.service.agent.capability;

import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRoleSettingsServiceTest {

    @Test
    void infersGeminiNativeFromTheDocumentUrlWithoutAProviderSetting() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("document_base_url")).thenReturn("https://api.apilio.ai/v1beta");
        when(settings.getValue("document_api_key")).thenReturn("secret");
        when(settings.getValue("document_model")).thenReturn("gemini-2.5-flash");

        AiRoleSettings resolved = new AiRoleSettingsService(settings).resolve(AiModelRole.DOCUMENT);

        assertThat(resolved.transport()).isEqualTo("GEMINI_NATIVE");
        assertThat(resolved.provider()).isEqualTo("gemini");
    }

    @Test
    void keepsOpenAiCompatibleDocumentGatewaysOnTheCompatibleProtocol() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("document_base_url")).thenReturn("https://gateway.example/v1");
        when(settings.getValue("document_api_key")).thenReturn("secret");
        when(settings.getValue("document_model")).thenReturn("vision-model");

        AiRoleSettings resolved = new AiRoleSettingsService(settings).resolve(AiModelRole.DOCUMENT);

        assertThat(resolved.transport()).isEqualTo("OPENAI_COMPATIBLE");
    }

    @Test
    void resolvesDraftValuesWithoutReplacingThePersistedConfiguration() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("document_api_key")).thenReturn("saved-secret");

        AiRoleSettings resolved = new AiRoleSettingsService(settings).resolveDraft(
                AiModelRole.DOCUMENT, "https://draft.example/v1", "vision-draft", "draft-secret");

        assertThat(resolved.baseUrl()).isEqualTo("https://draft.example/v1");
        assertThat(resolved.model()).isEqualTo("vision-draft");
        assertThat(resolved.apiKey()).isEqualTo("draft-secret");
        assertThat(resolved.signature()).isNotEqualTo("saved-secret");
    }

    @Test
    void usesSavedKeyOnlyWhenDraftKeyIsBlank() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getValue("api_key")).thenReturn("saved-secret");

        AiRoleSettings resolved = new AiRoleSettingsService(settings).resolveDraft(
                AiModelRole.CHAT, "https://draft.example/v1", "chat-draft", " ");

        assertThat(resolved.apiKey()).isEqualTo("saved-secret");
    }
}
