package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
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
     * <p>
     * 超时设为 60s 并开启 1 次重试：若 Provider 响应慢，可快速失败并触发重试，
     * 避免同步接口被单个慢请求挂死。
     */
    public ChatModel createChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(resolveBaseUrl())
                .apiKey(resolveApiKey())
                .modelName(resolveModel())
                .temperature(resolveTemperature())
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
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

    /**
     * 创建 Embedding 模型，用于 RAG 向量检索。
     * <p>
     * 优先读取 settings 中的 `embedding_base_url` / `embedding_model` / `embedding_api_key`；
     * 任一缺失时回退到主 LLM 配置。
     */
    public EmbeddingModel createEmbeddingModel() {
        String baseUrl = LLMConfigUtil.normalizeBaseUrl(settingsService.getValue("embedding_base_url"));
        if (baseUrl.isBlank()) {
            baseUrl = LLMConfigUtil.normalizeBaseUrl(settingsService.getValue("base_url"));
        }
        String model = settingsService.getValue("embedding_model");
        if (model == null || model.isBlank()) {
            model = settingsService.getValue("model");
        }
        String apiKey = settingsService.getValue("embedding_api_key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = settingsService.getValue("api_key");
        }
        if (baseUrl.isBlank() || model == null || model.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Embedding 配置不完整，请在设置页面填写 embedding/base_url、model、api_key");
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl + "/v1")
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build();
    }

    private String resolveApiKey() {
        return requireSetting("api_key", "API Key");
    }

    private String resolveModel() {
        return requireSetting("model", "模型");
    }

    private String resolveBaseUrl() {
        return LLMConfigUtil.normalizeBaseUrl(requireSetting("base_url", "Base URL")) + "/v1";
    }

    private double resolveTemperature() {
        return LLMConfigUtil.resolveTemperature(resolveModel());
    }

    private String requireSetting(String key, String displayName) {
        String value = settingsService.getValue(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(displayName + " 未配置，请在设置页面填写");
        }
        return value;
    }
}
