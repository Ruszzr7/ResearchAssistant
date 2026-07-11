package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import com.research.assistant.service.SettingsChangedEvent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

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
    private volatile ChatModel cachedChatModel;
    private volatile String cachedChatSignature;
    private volatile StreamingChatModel cachedStreamingModel;
    private volatile String cachedStreamingSignature;
    private volatile EmbeddingModel cachedEmbeddingModel;
    private volatile String cachedEmbeddingSignature;

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
        ChatSettings settings = resolveChatSettings();
        if (settings.signature().equals(cachedChatSignature) && cachedChatModel != null) {
            return cachedChatModel;
        }
        synchronized (this) {
            if (settings.signature().equals(cachedChatSignature) && cachedChatModel != null) {
                return cachedChatModel;
            }
            cachedChatModel = OpenAiChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.model())
                    .temperature(settings.temperature())
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(1)
                    .build();
            cachedChatSignature = settings.signature();
            return cachedChatModel;
        }
    }

    /**
     * 创建流式 StreamingChatModel。
     * <p>
     * 当前流式服务保留手动 SSE fallback；该模型供兼容的流式调用场景使用。
     */
    public StreamingChatModel createStreamingModel() {
        ChatSettings settings = resolveChatSettings();
        if (settings.signature().equals(cachedStreamingSignature) && cachedStreamingModel != null) {
            return cachedStreamingModel;
        }
        synchronized (this) {
            if (settings.signature().equals(cachedStreamingSignature) && cachedStreamingModel != null) {
                return cachedStreamingModel;
            }
            cachedStreamingModel = OpenAiStreamingChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.model())
                    .temperature(settings.temperature())
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(180))
                    .build();
            cachedStreamingSignature = settings.signature();
            return cachedStreamingModel;
        }
    }

    /**
     * 创建 Embedding 模型，用于 RAG 向量检索。
     * <p>
     * 优先读取 settings 中的 `embedding_base_url` / `embedding_model` / `embedding_api_key`；
     * 任一缺失时回退到主 LLM 配置。
     */
    public EmbeddingModel createEmbeddingModel() {
        EmbeddingSettings settings = resolveEmbeddingSettings();
        if (settings.signature().equals(cachedEmbeddingSignature) && cachedEmbeddingModel != null) {
            return cachedEmbeddingModel;
        }
        synchronized (this) {
            if (settings.signature().equals(cachedEmbeddingSignature) && cachedEmbeddingModel != null) {
                return cachedEmbeddingModel;
            }
            cachedEmbeddingModel = OpenAiEmbeddingModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.model())
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(1)
                    .build();
            cachedEmbeddingSignature = settings.signature();
            return cachedEmbeddingModel;
        }
    }

    /** 设置保存后刷新模型，下一次调用按新配置懒构建。 */
    @EventListener(SettingsChangedEvent.class)
    public void invalidate() {
        cachedChatModel = null;
        cachedChatSignature = null;
        cachedStreamingModel = null;
        cachedStreamingSignature = null;
        cachedEmbeddingModel = null;
        cachedEmbeddingSignature = null;
        log.info("模型配置缓存已刷新");
    }

    private ChatSettings resolveChatSettings() {
        String apiKey = requireSetting("api_key", "API Key");
        String model = requireSetting("model", "模型");
        String baseUrl = LLMConfigUtil.normalizeBaseUrl(requireSetting("base_url", "Base URL")) + "/v1";
        return new ChatSettings(baseUrl, apiKey, model, LLMConfigUtil.resolveTemperature(model));
    }

    private EmbeddingSettings resolveEmbeddingSettings() {
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
        return new EmbeddingSettings(baseUrl + "/v1", apiKey, model);
    }

    private String requireSetting(String key, String displayName) {
        String value = settingsService.getValue(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(displayName + " 未配置，请在设置页面填写");
        }
        return value;
    }

    private record ChatSettings(String baseUrl, String apiKey, String model, double temperature) {
        String signature() {
            return baseUrl + "\u0000" + apiKey + "\u0000" + model + "\u0000" + temperature;
        }
    }

    private record EmbeddingSettings(String baseUrl, String apiKey, String model) {
        String signature() {
            return baseUrl + "\u0000" + apiKey + "\u0000" + model;
        }
    }
}
