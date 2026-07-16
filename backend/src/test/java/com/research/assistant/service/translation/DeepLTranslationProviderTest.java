package com.research.assistant.service.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.reliability.ExternalCallPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepLTranslationProviderTest {

    private SettingsService settingsService;
    private DeepLHttpTransport transport;
    private ExternalCallPolicy policy;
    private DeepLTranslationProvider provider;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        transport = mock(DeepLHttpTransport.class);
        policy = new ExternalCallPolicy();
        provider = new DeepLTranslationProvider(settingsService,
                new ObjectMapper().findAndRegisterModules(), transport, policy);
        when(settingsService.getValue("deepl_auth_key")).thenReturn("test-key:fx");
        when(settingsService.getValue("deepl_api_base_url")).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        policy.shutdown();
    }

    @Test
    void usesFreeEndpointHeaderAndXmlTagProtectionOptions() throws Exception {
        when(transport.post(any(), anyString(), anyString(), any()))
                .thenReturn(new DeepLHttpTransport.Response(200, """
                        {"translations":[{"detected_source_language":"EN",
                        "text":"<ra>该速率 <keep>$R_k$</keep></ra>"}]}
                        """));

        var result = provider.translate(List.of("<ra>The rate <keep>$R_k$</keep></ra>"),
                TranslationLanguage.ENGLISH, TranslationLanguage.CHINESE);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.text()).contains("该速率", "$R_k$");
            assertThat(item.detectedSourceLanguage()).isEqualTo("EN");
        });
        ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<String> authorization = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(transport).post(endpoint.capture(), authorization.capture(), body.capture(), any());
        assertThat(endpoint.getValue().toString()).isEqualTo(DeepLTranslationProvider.FREE_ENDPOINT);
        assertThat(authorization.getValue()).isEqualTo("DeepL-Auth-Key test-key:fx");
        assertThat(body.getValue()).contains(
                "\"target_lang\":\"ZH-HANS\"",
                "\"source_lang\":\"EN\"",
                "\"tag_handling\":\"xml\"",
                "\"ignore_tags\":[\"keep\"]");
        assertThat(body.getValue()).doesNotContain("test-key");
    }

    @Test
    void mapsInvalidCredentialsWithoutRetryAndRetriesRateLimits() throws Exception {
        when(transport.post(any(), anyString(), anyString(), any()))
                .thenReturn(new DeepLHttpTransport.Response(403, "{}"));

        assertThatThrownBy(() -> provider.translate(List.of("<ra>text</ra>"),
                TranslationLanguage.ENGLISH, TranslationLanguage.CHINESE))
                .isInstanceOf(TranslationException.class)
                .satisfies(error -> {
                    TranslationException translationError = (TranslationException) error;
                    assertThat(translationError.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(translationError.retryable()).isFalse();
                });
        verify(transport, times(1)).post(any(), anyString(), anyString(), any());

        transport = mock(DeepLHttpTransport.class);
        provider = new DeepLTranslationProvider(settingsService,
                new ObjectMapper(), transport, policy);
        when(transport.post(any(), anyString(), anyString(), any()))
                .thenReturn(new DeepLHttpTransport.Response(429, "{}"));

        assertThatThrownBy(() -> provider.translate(List.of("<ra>text</ra>"),
                TranslationLanguage.ENGLISH, TranslationLanguage.CHINESE))
                .isInstanceOf(TranslationException.class)
                .satisfies(error -> assertThat(((TranslationException) error).retryable()).isTrue());
        verify(transport, times(2)).post(any(), anyString(), anyString(), any());
    }
}
