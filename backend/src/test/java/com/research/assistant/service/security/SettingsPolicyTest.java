package com.research.assistant.service.security;

import com.research.assistant.entity.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsPolicyTest {

    @Test
    void masksSecretsAndRecognisesProviderCredentials() {
        assertThat(SettingsPolicy.isSensitive("qdrant_api_key")).isTrue();
        assertThat(SettingsPolicy.isSensitive("apiKey")).isTrue();
        assertThat(SettingsPolicy.isSensitive("provider_token")).isTrue();
        assertThat(SettingsPolicy.isSensitive("deepl_auth_key")).isTrue();
        assertThat(SettingsPolicy.mask("secret-value")).isEqualTo("secret****alue");
        assertThat(SettingsPolicy.isMaskedValue("secret****alue")).isTrue();
    }

    @Test
    void validatesAllowedSettingsAndRejectsUnknownOrInvalidValues() {
        SettingsPolicy.validateBatch(List.of(new Settings("base_url", "https://example.com/v1")));
        SettingsPolicy.validateBatch(List.of(
                new Settings("translation_provider", "deepl"),
                new Settings("deepl_api_base_url", "https://api-free.deepl.com")));
        SettingsPolicy.validateBatch(List.of(
                new Settings("ai_provider", "kimi"),
                new Settings("ai_channel", "coding")));

        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("unknown_key", "value"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("retired_external_setting", "true"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("translation_provider", "llm"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("ai_provider", "unknown"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("ai_channel", "enterprise"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
