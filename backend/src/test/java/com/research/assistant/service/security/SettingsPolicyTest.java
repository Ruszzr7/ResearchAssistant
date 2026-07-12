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
        assertThat(SettingsPolicy.isSensitive("provider_token")).isTrue();
        assertThat(SettingsPolicy.mask("secret-value")).isEqualTo("secret****alue");
        assertThat(SettingsPolicy.isMaskedValue("secret****alue")).isTrue();
    }

    @Test
    void validatesAllowedSettingsAndRejectsUnknownOrInvalidValues() {
        SettingsPolicy.validateBatch(List.of(new Settings("base_url", "https://example.com/v1")));

        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("unknown_key", "value"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsPolicy.validateBatch(
                List.of(new Settings("openalex_enabled", "yes"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
