package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import com.research.assistant.service.SettingsChangedEvent;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiProviderRegistry;
import com.research.assistant.service.ai.provider.TokenLimitParameter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import com.research.assistant.service.agent.capability.AiModelRole;
import com.research.assistant.service.agent.capability.AiRoleSettings;
import com.research.assistant.service.agent.capability.AiRoleSettingsService;
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
    private volatile ChatModel cachedAgentChatModel;
    private volatile String cachedAgentChatSignature;
    private volatile StreamingChatModel cachedStreamingModel;
    private volatile String cachedStreamingSignature;
    private final AiRoleSettingsService roleSettingsService;
    private volatile ChatModel cachedDocumentModel;
    private volatile String cachedDocumentSignature;

    @org.springframework.beans.factory.annotation.Autowired
    public LangChain4jModelFactory(SettingsService settingsService,
                                   @org.springframework.context.annotation.Lazy AiRoleSettingsService roleSettingsService) {
        this.settingsService = settingsService;
        this.roleSettingsService = roleSettingsService;
    }

    /** Constructor retained for focused tests that only exercise the chat model. */
    public LangChain4jModelFactory(SettingsService settingsService) {
        this(settingsService, null);
    }

    /**
     * 创建同步 ChatModel。
     * <p>
     * 超时设为 60s 并开启 1 次重试：若 Provider 响应慢，可快速失败并触发重试，
     * 避免同步接口被单个慢请求挂死。
     */
    public ChatModel createChatModel() {
        return createChatModel(true);
    }

    /**
     * Agent turns use one bounded provider attempt. A second blind 60-second
     * retry would consume the whole durable run deadline before the Agent can
     * report which stage failed.
     */
    public ChatModel createAgentChatModel() {
        return createChatModel(false);
    }

    /** Build an agent model from one-shot settings used by the capability probe. */
    public ChatModel createAgentChatModel(AiRoleSettings settings) {
        if (settings == null || settings.role() != AiModelRole.CHAT) {
            throw new IllegalArgumentException("chat role settings are required");
        }
        AiProviderProfile profile = profile(settings);
        return buildAgentChatModel(new ChatSettings(settings.baseUrl(), settings.apiKey(),
                settings.model(), profile), false);
    }

    private ChatModel createChatModel(boolean retry) {
        ChatSettings settings = resolveChatSettings();
        if (retry && settings.signature().equals(cachedChatSignature) && cachedChatModel != null) {
            return cachedChatModel;
        }
        if (!retry && settings.signature().equals(cachedAgentChatSignature) && cachedAgentChatModel != null) {
            return cachedAgentChatModel;
        }
        synchronized (this) {
            if (retry && settings.signature().equals(cachedChatSignature) && cachedChatModel != null) {
                return cachedChatModel;
            }
            if (!retry && settings.signature().equals(cachedAgentChatSignature) && cachedAgentChatModel != null) {
                return cachedAgentChatModel;
            }
            ChatModel model = buildAgentChatModel(settings, retry);
            if (retry) {
                cachedChatModel = model;
                cachedChatSignature = settings.signature();
            } else {
                cachedAgentChatModel = model;
                cachedAgentChatSignature = settings.signature();
            }
            return model;
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

    public ChatModel createDocumentModel() {
        if (roleSettingsService == null) throw new IllegalStateException("document role settings are unavailable");
        AiRoleSettings settings = roleSettingsService.resolve(AiModelRole.DOCUMENT);
        if (settings.signature().equals(cachedDocumentSignature) && cachedDocumentModel != null) return cachedDocumentModel;
        synchronized (this) {
            if (settings.signature().equals(cachedDocumentSignature) && cachedDocumentModel != null) return cachedDocumentModel;
            cachedDocumentModel = buildDocumentModel(settings);
            cachedDocumentSignature = settings.signature();
            return cachedDocumentModel;
        }
    }

    /** Build a document model from one-shot settings used by the capability probe. */
    public ChatModel createDocumentModel(AiRoleSettings settings) {
        if (settings == null || settings.role() != AiModelRole.DOCUMENT) {
            throw new IllegalArgumentException("document role settings are required");
        }
        return buildDocumentModel(settings);
    }

    /** 设置保存后刷新模型，下一次调用按新配置懒构建。 */
    @EventListener(SettingsChangedEvent.class)
    public void invalidate() {
        cachedChatModel = null;
        cachedChatSignature = null;
        cachedAgentChatModel = null;
        cachedAgentChatSignature = null;
        cachedStreamingModel = null;
        cachedStreamingSignature = null;
        cachedDocumentModel = null;
        cachedDocumentSignature = null;
        log.info("模型配置缓存已刷新");
    }

    private ChatSettings resolveChatSettings() {
        String apiKey = requireSetting("api_key", "API Key");
        String model = requireSetting("model", "模型");
        AiProviderProfile profile = currentProfile(model);
        return new ChatSettings(profile.baseUrl(), apiKey, model, profile);
    }

    private ChatModel buildAgentChatModel(ChatSettings settings, boolean retry) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.model())
                .timeout(Duration.ofSeconds(60))
                .maxRetries(retry ? 1 : 0);
        applyChatPolicy(builder, settings.profile(), !retry);
        if (!retry) {
            // Agent answers are deliberately concise and structured. A smaller visible
            // completion cap prevents a provider's hidden/reasoning budget from starving
            // the terminal answer and keeps one model turn bounded.
            int agentOutputLimit = Math.min(settings.profile().defaultMaxOutputTokens(), 2048);
            if (settings.profile().tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
                builder.maxCompletionTokens(agentOutputLimit);
            } else {
                builder.maxTokens(agentOutputLimit);
            }
        }
        if (!retry && settings.profile().provider() == com.research.assistant.service.ai.provider.AiProvider.KIMI
                && "coding".equals(settings.profile().channel())) {
            builder.customParameters(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private ChatModel buildDocumentModel(AiRoleSettings settings) {
        if ("GEMINI_NATIVE".equals(settings.transport())) {
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(settings.apiKey()).modelName(settings.model())
                    // Document understanding is one deliberate whole-paper call.
                    // Do not hide a second provider request behind the bounded
                    // call; the durable task must fail once and report the cause.
                    .timeout(Duration.ofSeconds(90)).maxRetries(0);
            if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) builder.baseUrl(settings.baseUrl());
            return builder.build();
        }
        var builder = OpenAiChatModel.builder().baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey()).modelName(settings.model())
                .timeout(Duration.ofSeconds(90)).maxRetries(0);
        AiProviderProfile profile = profile(settings);
        if (profile.provider() == com.research.assistant.service.ai.provider.AiProvider.KIMI
                && supportsKimiStructuredExtraction(settings.model())) {
            // Kimi K2.5/K2.6 enables hidden thinking by default. Paper understanding
            // is bounded JSON extraction, so keep the document call responsive;
            // Agent chat keeps its independent thinking policy.
            builder.customParameters(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private boolean supportsKimiStructuredExtraction(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("kimi-k2.5") || normalized.equals("kimi-k2.6");
    }

    private AiProviderProfile profile(AiRoleSettings settings) {
        return AiProviderRegistry.resolve(settings.provider(), settings.channel(),
                settings.baseUrl(), settings.model());
    }

    public AiProviderProfile currentProfile() {
        return currentProfile(requireSetting("model", "模型"));
    }

    private AiProviderProfile currentProfile(String model) {
        return AiProviderRegistry.resolve(
                null,
                null,
                settingsService.getValue("base_url"),
                model);
    }

    private void applyChatPolicy(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                 AiProviderProfile profile, boolean includeThinking) {
        if (profile.temperature() != null) builder.temperature(profile.temperature());
        if (profile.tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
            builder.maxCompletionTokens(profile.defaultMaxOutputTokens());
        } else {
            builder.maxTokens(profile.defaultMaxOutputTokens());
        }
        if (profile.reasoning()) builder.returnThinking(includeThinking);
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

}
