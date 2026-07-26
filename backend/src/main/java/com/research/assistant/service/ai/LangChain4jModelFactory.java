package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import com.research.assistant.service.SettingsChangedEvent;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiProviderRegistry;
import com.research.assistant.service.ai.provider.TokenLimitParameter;
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
            var builder = OpenAiChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.model())
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(1);
            applyChatPolicy(builder, settings.profile());
            cachedChatModel = builder.build();
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
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.model())
                    .timeout(Duration.ofSeconds(180));
            applyStreamingPolicy(builder, settings.profile());
            cachedStreamingModel = builder.build();
            cachedStreamingSignature = settings.signature();
            return cachedStreamingModel;
        }
    }

    /**
     * 创建 Embedding 模型，用于 RAG 向量检索。
     * <p>
     * 只读取独立的 `embedding_base_url` / `embedding_model` / `embedding_api_key`；
     * 任一缺失时由上层降级到关键词检索，避免把聊天模型误当作 Embedding 模型。
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
        AiProviderProfile profile = currentProfile(model);
        return new ChatSettings(profile.baseUrl(), apiKey, model, profile);
    }

    private EmbeddingSettings resolveEmbeddingSettings() {
        String baseUrl = LLMConfigUtil.normalizeBaseUrl(settingsService.getValue("embedding_base_url"));
        String model = settingsService.getValue("embedding_model");
        String apiKey = settingsService.getValue("embedding_api_key");
        if (baseUrl.isBlank() || model == null || model.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Embedding 未独立配置，RAG 将降级为关键词检索");
        }
        return new EmbeddingSettings(baseUrl, apiKey, model);
    }

    public AiProviderProfile currentProfile() {
        return currentProfile(requireSetting("model", "模型"));
    }

    private AiProviderProfile currentProfile(String model) {
        return AiProviderRegistry.resolve(
                settingsService.getValue("ai_provider"),
                settingsService.getValue("ai_channel"),
                settingsService.getValue("base_url"),
                model);
    }

    private void applyChatPolicy(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                 AiProviderProfile profile) {
        if (profile.temperature() != null) builder.temperature(profile.temperature());
        if (profile.tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
            builder.maxCompletionTokens(profile.defaultMaxOutputTokens());
        } else {
            builder.maxTokens(profile.defaultMaxOutputTokens());
        }
        if (profile.reasoning()) builder.returnThinking(true);
    }

    private void applyStreamingPolicy(
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
            AiProviderProfile profile) {
        if (profile.temperature() != null) builder.temperature(profile.temperature());
        if (profile.tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
            builder.maxCompletionTokens(profile.defaultMaxOutputTokens());
        } else {
            builder.maxTokens(profile.defaultMaxOutputTokens());
        }
        if (profile.reasoning()) builder.returnThinking(true);
    }

    private String requireSetting(String key, String displayName) {
        String value = settingsService.getValue(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException(displayName + " 未配置，请在设置页面填写");
        }
        return value;
    }

    private record ChatSettings(String baseUrl, String apiKey, String model,
                                AiProviderProfile profile) {
        String signature() {
            return baseUrl + "\u0000" + apiKey + "\u0000" + model + "\u0000" + profile;
        }
    }

    private record EmbeddingSettings(String baseUrl, String apiKey, String model) {
        String signature() {
            return baseUrl + "\u0000" + apiKey + "\u0000" + model;
        }
    }
}
