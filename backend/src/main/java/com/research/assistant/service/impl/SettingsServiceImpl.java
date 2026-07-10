package com.research.assistant.service.impl;

import com.research.assistant.entity.Settings;
import com.research.assistant.mapper.SettingsMapper;
import com.research.assistant.service.Encryptor;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Settings 服务实现。
 * <p>
 * - 对以 {@code api_key} 结尾的敏感 key 自动加解密。
 * - 支持环境变量覆盖（RA_API_KEY、RA_BASE_URL、RA_MODEL 等）。
 * - saveAll 使用 saveOrUpdate 逐条处理（数据量极小，无需批量优化）。
 */
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

    /** @Lazy 打破与 LLMServiceImpl 之间的循环依赖 */
    public SettingsServiceImpl(SettingsMapper settingsMapper, @Lazy LLMService llmService,
                               Encryptor encryptor, Environment environment) {
        this.settingsMapper = settingsMapper;
        this.llmService = llmService;
        this.encryptor = encryptor;
        this.environment = environment;
    }

    @Override
    public List<Settings> getAll() {
        List<Settings> list = settingsMapper.selectList(null);
        for (Settings s : list) {
            s.setValue(decryptForDisplay(s.getKeyName(), s.getValue()));
        }
        return list;
    }

    @Override
    public String getValue(String keyName) {
        String env = environmentOverride(keyName);
        if (env != null) return env;

        Settings s = settingsMapper.selectByKey(keyName);
        if (s == null) return null;
        return decryptForDisplay(keyName, s.getValue());
    }

    @Override
    @Transactional
    public void saveAll(List<Settings> settings) {
        for (Settings s : settings) {
            String value = encryptor.encryptIfNeeded(s.getKeyName(), s.getValue());
            s.setValue(value);
            Settings existing = settingsMapper.selectByKey(s.getKeyName());
            if (existing != null) {
                s.setId(existing.getId());
                settingsMapper.updateById(s);
            } else {
                settingsMapper.insert(s);
            }
        }
    }

    @Override
    public boolean testConnection() {
        String result = llmService.chat("Reply with exactly one word: OK", "ping");
        return result != null && !result.isEmpty();
    }

    private String decryptForDisplay(String keyName, String value) {
        if (!encryptor.shouldEncrypt(keyName)) return value;
        String decrypted = encryptor.decryptIfNeeded(value);
        // 解密失败时返回原值，避免用 null 覆盖数据库
        return decrypted != null ? decrypted : value;
    }

    private String environmentOverride(String keyName) {
        String envKey = ENV_OVERRIDES.get(keyName);
        if (envKey == null) return null;
        String value = environment.getProperty(envKey);
        return value != null && !value.isBlank() ? value : null;
    }
}
