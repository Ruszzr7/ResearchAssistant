package com.research.assistant.service.impl;

import com.research.assistant.entity.Settings;
import com.research.assistant.mapper.SettingsMapper;
import com.research.assistant.service.Encryptor;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsChangedEvent;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.security.SettingsPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Settings persistence and secret handling. */
@Service
public class SettingsServiceImpl implements SettingsService {

    private static final Map<String, String> ENV_OVERRIDES = Map.of(
            "api_key", "RA_API_KEY",
            "base_url", "RA_BASE_URL",
            "model", "RA_MODEL",
            "embedding_api_key", "RA_EMBEDDING_API_KEY",
            "embedding_base_url", "RA_EMBEDDING_BASE_URL",
            "embedding_model", "RA_EMBEDDING_MODEL"
    );

    private final SettingsMapper settingsMapper;
    private final LLMService llmService;
    private final Encryptor encryptor;
    private final Environment environment;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, String> valueCache = new ConcurrentHashMap<>();

    @Autowired
    public SettingsServiceImpl(SettingsMapper settingsMapper, @Lazy LLMService llmService,
                               Encryptor encryptor, Environment environment,
                               ApplicationEventPublisher eventPublisher) {
        this.settingsMapper = settingsMapper;
        this.llmService = llmService;
        this.encryptor = encryptor;
        this.environment = environment;
        this.eventPublisher = eventPublisher;
    }

    /** Constructor retained for focused unit tests. */
    public SettingsServiceImpl(SettingsMapper settingsMapper, LLMService llmService,
                               Encryptor encryptor, Environment environment) {
        this(settingsMapper, llmService, encryptor, environment, event -> { });
    }

    @Override
    public List<Settings> getAll() {
        List<Settings> list = settingsMapper.selectList(null);
        for (Settings setting : list) {
            String value = decryptForDisplay(setting.getKeyName(), setting.getValue());
            setting.setConfigured(value != null && !value.isBlank());
            setting.setValue(SettingsPolicy.isSensitive(setting.getKeyName())
                    ? SettingsPolicy.mask(value) : value);
        }
        return list;
    }

    @Override
    public String getValue(String keyName) {
        String env = environmentOverride(keyName);
        if (env != null) return env;

        String cached = valueCache.get(keyName);
        if (cached != null) return cached;

        Settings setting = settingsMapper.selectByKey(keyName);
        if (setting == null) return null;
        String value = decryptForDisplay(keyName, setting.getValue());
        if (value != null) valueCache.put(keyName, value);
        return value;
    }

    @Override
    @Transactional
    public void saveAll(List<Settings> settings) {
        SettingsPolicy.validateBatch(settings);
        for (Settings setting : settings) {
            Settings existing = settingsMapper.selectByKey(setting.getKeyName());
            if (SettingsPolicy.isSensitive(setting.getKeyName())
                    && SettingsPolicy.isMaskedValue(setting.getValue())) {
                if (existing == null) {
                    throw new IllegalArgumentException("敏感设置尚未配置，不能保存脱敏占位符");
                }
                continue;
            }

            String value = encryptor.encryptIfNeeded(setting.getKeyName(),
                    setting.getValue() == null ? "" : setting.getValue());
            setting.setValue(value);
            if (existing != null) {
                setting.setId(existing.getId());
                settingsMapper.updateById(setting);
            } else {
                settingsMapper.insert(setting);
            }
            valueCache.remove(setting.getKeyName());
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(this));
    }

    @Override
    public boolean testConnection() {
        String result = llmService.chat("Reply with exactly one word: OK", "ping");
        return result != null && !result.isEmpty();
    }

    private String decryptForDisplay(String keyName, String value) {
        if (!encryptor.shouldEncrypt(keyName)) return value;
        return encryptor.decryptIfNeeded(value);
    }

    private String environmentOverride(String keyName) {
        String envKey = ENV_OVERRIDES.get(keyName);
        if (envKey == null) return null;
        String value = environment.getProperty(envKey);
        return value != null && !value.isBlank() ? value : null;
    }
}
