package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LangChain4j 模型工厂 —— 从 settings 表热读配置，构建 OpenAI 兼容的 ChatModel。
 * <p>
 * 不使用 langchain4j-spring-boot-starter 的自动配置，因为 api_key / base_url / model
 * 都存在 DB 中，需要运行时动态读取。
 */
@Component
public class LangChain4jModelFactory {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jModelFactory.class);

    private final SettingsService settingsService;

    public LangChain4jModelFactory(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 创建同步 ChatModel。
     */
    public ChatModel createChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(resolveBaseUrl())
                .apiKey(resolveApiKey())
                .modelName(resolveModel())
                .temperature(resolveTemperature())
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 创建流式 StreamingChatModel。
     * <p>
     * Phase 0 暂不由 LLMStreamService 使用，供后续 Phase 5 流式重构时直接注入。
     */
    public StreamingChatModel createStreamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(resolveBaseUrl())
                .apiKey(resolveApiKey())
                .modelName(resolveModel())
                .temperature(resolveTemperature())
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(180))
                .build();
    }

    // ========== 配置解析 ==========

    private String resolveApiKey() {
        return requireSetting("api_key", "API Key");
    }

    private String resolveModel() {
        return requireSetting("model", "模型");
    }

    private String resolveBaseUrl() {
        String raw = requireSetting("base_url", "Base URL");
        String normalized = raw.trim();

        // 去掉末尾斜杠
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // 去掉重复的 /v1，后面 LangChain4j 会自己追加 /chat/completions
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }

        // 再次去掉可能暴露的末尾斜杠
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized + "/v1";
    }

    private double resolveTemperature() {
        String model = resolveModel();
        // kimi-k2.7-code 官方要求 temperature 固定为 1.0
        if (model != null && model.toLowerCase().contains("kimi-k2.7-code")) {
            return 1.0;
        }
        return 0.3;
    }

    private String requireSetting(String key, String displayName) {
        String value = settingsService.getValue(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(displayName + " 未配置，请在设置页面填写");
        }
        return value;
    }
}
