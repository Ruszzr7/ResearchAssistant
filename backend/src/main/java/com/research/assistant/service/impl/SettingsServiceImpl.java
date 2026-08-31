package com.research.assistant.service.impl;

import com.research.assistant.entity.Settings;
import com.research.assistant.dto.AiConnectionTestResult;
import com.research.assistant.mapper.SettingsMapper;
import com.research.assistant.service.Encryptor;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsChangedEvent;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.security.SettingsPolicy;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiProviderRegistry;
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

    private static final Map<String, String> ENV_OVERRIDES = Map.ofEntries(
            Map.entry("api_key", "RA_API_KEY"),
            Map.entry("ai_provider", "RA_AI_PROVIDER"),
            Map.entry("ai_channel", "RA_AI_CHANNEL"),
            Map.entry("base_url", "RA_BASE_URL"),
            Map.entry("model", "RA_MODEL"),
            Map.entry("document_api_key", "RA_DOCUMENT_API_KEY"),
            Map.entry("document_base_url", "RA_DOCUMENT_BASE_URL"),
            Map.entry("document_model", "RA_DOCUMENT_MODEL"),
            Map.entry("document_ai_transport", "RA_DOCUMENT_AI_TRANSPORT"),
            Map.entry("translation_provider", "RA_TRANSLATION_PROVIDER"),
            Map.entry("deepl_auth_key", "DEEPL_AUTH_KEY"),
            Map.entry("deepl_api_base_url", "DEEPL_API_BASE_URL")
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
    public AiConnectionTestResult testConnection() {
        AiProviderProfile profile = AiProviderRegistry.resolve(
                null, null,
                getValue("base_url"), getValue("model"));
        Map<String, String> capabilities = Map.of(
                "chat", "待验证",
                "stream", profile.manualStreaming() ? "兼容 SSE" : "标准 SSE",
                "structured", profile.jsonResponseFormat() ? "JSON 模式" : "Prompt 约束",
                "vision", profile.vision() ? "支持" : "不支持",
                "retrieval", "本地版面与关键词检索");
        try {
            String result = llmService.chat("Reply with exactly one word: OK", "ping");
            boolean ok = result != null && !result.isBlank();
            Map<String, String> verified = new java.util.LinkedHashMap<>(capabilities);
            verified.put("chat", ok ? "已验证" : "响应为空");
            return new AiConnectionTestResult(ok,
                    ok ? "连接成功" : "模型返回了空响应",
                    profile.providerValue(), profile.channel(), Map.copyOf(verified));
        } catch (RuntimeException exception) {
            Map<String, String> failed = new java.util.LinkedHashMap<>(capabilities);
            failed.put("chat", "失败");
            return new AiConnectionTestResult(false, safeConnectionMessage(exception),
                    profile.providerValue(), profile.channel(), Map.copyOf(failed));
        }
    }

    private String safeConnectionMessage(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        return switch (type) {
            case "AuthenticationException" -> "认证失败，请检查 API Key 和套餐权限";
            case "ModelNotFoundException" -> "模型不存在或当前账号无权使用";
            case "RateLimitException" -> "请求受限，请检查额度、套餐或稍后重试";
            case "InvalidRequestException" -> "供应商拒绝了请求，请检查模型、通道和参数规则";
            case "TimeoutException" -> "连接供应商超时";
            default -> "上游模型服务暂时不可用";
        };
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
