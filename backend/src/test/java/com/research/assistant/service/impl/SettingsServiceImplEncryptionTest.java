package com.research.assistant.service.impl;

import com.research.assistant.entity.Settings;
import com.research.assistant.mapper.SettingsMapper;
import com.research.assistant.service.Encryptor;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SettingsServiceImplEncryptionTest {

    private SettingsMapper settingsMapper;
    private LLMService llmService;
    private Encryptor encryptor;
    private Environment environment;
    private SettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        settingsMapper = mock(SettingsMapper.class);
        llmService = mock(LLMService.class);
        encryptor = new Encryptor("test-master-key");
        environment = mock(Environment.class);
        service = new SettingsServiceImpl(settingsMapper, llmService, encryptor, environment);
    }

    @Test
    void saveAllEncryptsApiKey() {
        Settings s = new Settings();
        s.setKeyName("api_key");
        s.setValue("sk-test");
        when(settingsMapper.selectByKey("api_key")).thenReturn(null);

        service.saveAll(List.of(s));

        verify(settingsMapper).insert(argThat((Settings saved) ->
                saved.getValue() != null && saved.getValue().startsWith("enc:")));
    }

    @Test
    void getValueDecryptsApiKey() {
        String encrypted = encryptor.encryptIfNeeded("api_key", "sk-test");
        Settings s = new Settings();
        s.setKeyName("api_key");
        s.setValue(encrypted);
        when(settingsMapper.selectByKey("api_key")).thenReturn(s);

        String value = service.getValue("api_key");

        assertThat(value).isEqualTo("sk-test");
    }

    @Test
    void getValuePrefersEnvironmentOverride() {
        when(environment.getProperty("RA_API_KEY")).thenReturn("sk-from-env");

        String value = service.getValue("api_key");

        assertThat(value).isEqualTo("sk-from-env");
        verify(settingsMapper, never()).selectByKey(anyString());
    }

    @Test
    void translationCredentialsPreferEnvironmentOverride() {
        when(environment.getProperty("DEEPL_AUTH_KEY")).thenReturn("deepl-from-env");

        assertThat(service.getValue("deepl_auth_key")).isEqualTo("deepl-from-env");
        verify(settingsMapper, never()).selectByKey("deepl_auth_key");
    }

    @Test
    void getValueReturnsNullWhenNotFoundAndNoEnvOverride() {
        when(settingsMapper.selectByKey("model")).thenReturn(null);
        when(environment.getProperty("RA_MODEL")).thenReturn(null);

        String value = service.getValue("model");

        assertThat(value).isNull();
    }

    @Test
    void getAllMasksSensitiveValuesAndKeepsConfiguredFlag() {
        Settings setting = new Settings("ieee_xplore_api_key",
                encryptor.encryptIfNeeded("ieee_xplore_api_key", "ieee-secret"));
        when(settingsMapper.selectList(null)).thenReturn(List.of(setting));

        List<Settings> result = service.getAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("ieee-s****cret");
        assertThat(result.get(0).getConfigured()).isTrue();
    }
}
