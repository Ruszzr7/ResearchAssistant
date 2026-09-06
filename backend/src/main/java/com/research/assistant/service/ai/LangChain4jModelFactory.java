package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsChangedEvent;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.agent.capability.AiSettings;
import com.research.assistant.service.agent.capability.AiSettingsService;
import com.research.assistant.service.ai.provider.AiProvider;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import com.research.assistant.service.ai.provider.AiProviderRegistry;
import com.research.assistant.service.ai.provider.TokenLimitParameter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds every model client from one user-selected multimodal API configuration. */
@Component
public class LangChain4jModelFactory {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jModelFactory.class);

    private final AiSettingsService aiSettingsService;
    private volatile ChatModel cachedChatModel;
    private volatile String cachedChatSignature;
    private volatile ChatModel cachedAgentModel;
    private volatile String cachedAgentSignature;
    private volatile StreamingChatModel cachedStreamingModel;
    private volatile String cachedStreamingSignature;
    private volatile ChatModel cachedPaperModel;
    private volatile String cachedPaperSignature;

    @org.springframework.beans.factory.annotation.Autowired
    public LangChain4jModelFactory(SettingsService settingsService,
                                   @org.springframework.context.annotation.Lazy AiSettingsService aiSettingsService) {
        this.aiSettingsService = aiSettingsService;
    }

    /** Constructor retained for focused tests. */
    public LangChain4jModelFactory(SettingsService settingsService) {
        this(settingsService, new AiSettingsService(settingsService));
    }

    /** General text chat keeps one retry for the synchronous endpoint. */
    public ChatModel createChatModel() {
        return cachedModel("chat", true);
    }

    /** Agent turns use one provider attempt and a bounded visible answer. */
    public ChatModel createAgentChatModel() {
        return cachedModel("agent", false);
    }

    public ChatModel createAgentChatModel(AiSettings settings) {
        return buildChatModel(settings, false, true);
    }

    private ChatModel cachedModel(String purpose, boolean retry) {
        AiSettings settings = aiSettingsService.resolve();
        String signature = settings.signature() + ":" + purpose;
        ChatModel cached = retry ? cachedChatModel : cachedAgentModel;
        String cachedSignature = retry ? cachedChatSignature : cachedAgentSignature;
        if (signature.equals(cachedSignature) && cached != null) return cached;
        synchronized (this) {
            cached = retry ? cachedChatModel : cachedAgentModel;
            cachedSignature = retry ? cachedChatSignature : cachedAgentSignature;
            if (signature.equals(cachedSignature) && cached != null) return cached;
            ChatModel model = buildChatModel(settings, retry, !retry);
            if (retry) {
                cachedChatModel = model;
                cachedChatSignature = signature;
            } else {
                cachedAgentModel = model;
                cachedAgentSignature = signature;
            }
            return model;
        }
    }

    public StreamingChatModel createStreamingModel() {
        AiSettings settings = aiSettingsService.resolve();
        if (settings.signature().equals(cachedStreamingSignature) && cachedStreamingModel != null) {
            return cachedStreamingModel;
        }
        synchronized (this) {
            if (settings.signature().equals(cachedStreamingSignature) && cachedStreamingModel != null) {
                return cachedStreamingModel;
            }
            AiProviderProfile profile = profile(settings);
            if ("GEMINI_NATIVE".equals(settings.transport())) {
                var builder = GoogleAiGeminiStreamingChatModel.builder()
                        .apiKey(settings.apiKey()).modelName(settings.model()).timeout(Duration.ofSeconds(180));
                if (!settings.baseUrl().isBlank()) builder.baseUrl(settings.baseUrl());
                if (profile.temperature() != null) builder.temperature(profile.temperature());
                builder.maxOutputTokens(profile.defaultMaxOutputTokens());
                cachedStreamingModel = builder.build();
            } else {
                var builder = OpenAiStreamingChatModel.builder()
                        .baseUrl(settings.baseUrl()).apiKey(settings.apiKey()).modelName(settings.model())
                        .timeout(Duration.ofSeconds(180));
                applyStreamingPolicy(builder, profile);
                cachedStreamingModel = builder.build();
            }
            cachedStreamingSignature = settings.signature();
            return cachedStreamingModel;
        }
    }

    public ChatModel createPaperUnderstandingModel() {
        AiSettings settings = aiSettingsService.resolve();
        if (settings.signature().equals(cachedPaperSignature) && cachedPaperModel != null) return cachedPaperModel;
        synchronized (this) {
            if (settings.signature().equals(cachedPaperSignature) && cachedPaperModel != null) return cachedPaperModel;
            cachedPaperModel = buildPaperModel(settings);
            cachedPaperSignature = settings.signature();
            return cachedPaperModel;
        }
    }

    public ChatModel createPaperUnderstandingModel(AiSettings settings) {
        return buildPaperModel(settings);
    }

    @EventListener(SettingsChangedEvent.class)
    public void invalidate() {
        cachedChatModel = null;
        cachedChatSignature = null;
        cachedAgentModel = null;
        cachedAgentSignature = null;
        cachedStreamingModel = null;
        cachedStreamingSignature = null;
        cachedPaperModel = null;
        cachedPaperSignature = null;
        log.info("模型配置缓存已刷新");
    }

    private ChatModel buildChatModel(AiSettings settings, boolean retry, boolean agent) {
        AiProviderProfile profile = profile(settings);
        int outputLimit = agent ? Math.min(profile.defaultMaxOutputTokens(), 2048)
                : profile.defaultMaxOutputTokens();
        if ("GEMINI_NATIVE".equals(settings.transport())) {
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(settings.apiKey()).modelName(settings.model())
                    .timeout(Duration.ofSeconds(agent ? 80 : 60)).maxRetries(retry ? 1 : 0)
                    .maxOutputTokens(outputLimit);
            if (!settings.baseUrl().isBlank()) builder.baseUrl(settings.baseUrl());
            if (profile.temperature() != null) builder.temperature(profile.temperature());
            return builder.build();
        }
        var builder = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl()).apiKey(settings.apiKey()).modelName(settings.model())
                .timeout(Duration.ofSeconds(agent ? 80 : 60)).maxRetries(retry ? 1 : 0);
        applyChatPolicy(builder, profile, agent);
        if (profile.tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
            builder.maxCompletionTokens(outputLimit);
        } else {
            builder.maxTokens(outputLimit);
        }
        if (agent && profile.provider() == AiProvider.KIMI && "coding".equals(profile.channel())) {
            builder.customParameters(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private ChatModel buildPaperModel(AiSettings settings) {
        AiProviderProfile profile = profile(settings);
        if ("GEMINI_NATIVE".equals(settings.transport())) {
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(settings.apiKey()).modelName(settings.model())
                    .timeout(Duration.ofSeconds(90)).maxRetries(0)
                    .maxOutputTokens(profile.defaultMaxOutputTokens());
            if (!settings.baseUrl().isBlank()) builder.baseUrl(settings.baseUrl());
            if (profile.temperature() != null) builder.temperature(profile.temperature());
            return builder.build();
        }
        var builder = OpenAiChatModel.builder().baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey()).modelName(settings.model())
                .timeout(Duration.ofSeconds(90)).maxRetries(0);
        if (profile.provider() == AiProvider.KIMI && supportsKimiStructuredExtraction(settings.model())) {
            builder.customParameters(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private boolean supportsKimiStructuredExtraction(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("kimi-k2.");
    }

    private AiProviderProfile profile(AiSettings settings) {
        return AiProviderRegistry.resolve(settings.provider(), settings.channel(),
                settings.baseUrl(), settings.model());
    }

    public AiProviderProfile currentProfile() {
        return profile(aiSettingsService.resolve());
    }

    private void applyChatPolicy(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                 AiProviderProfile profile, boolean includeThinking) {
        if (profile.temperature() != null) builder.temperature(profile.temperature());
        if (profile.reasoning()) builder.returnThinking(includeThinking);
    }

    private void applyStreamingPolicy(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                                      AiProviderProfile profile) {
        if (profile.temperature() != null) builder.temperature(profile.temperature());
        if (profile.tokenLimitParameter() == TokenLimitParameter.MAX_COMPLETION_TOKENS) {
            builder.maxCompletionTokens(profile.defaultMaxOutputTokens());
        } else {
            builder.maxTokens(profile.defaultMaxOutputTokens());
        }
        if (profile.reasoning()) builder.returnThinking(true);
    }
}
