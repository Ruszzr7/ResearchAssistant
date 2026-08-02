package com.research.assistant.service.ai.provider;

import com.research.assistant.service.ai.LLMConfigUtil;

import java.util.Locale;
import java.util.Set;

/** Stable provider profiles; model IDs remain user-editable because they change frequently. */
public final class AiProviderRegistry {

    private AiProviderRegistry() {
    }

    public static AiProviderProfile resolve(String configuredProvider,
                                            String configuredChannel,
                                            String configuredBaseUrl,
                                            String model) {
        AiProvider provider = AiProvider.resolve(configuredProvider, configuredBaseUrl, model);
        String channel = normalizeChannel(provider, configuredChannel, configuredBaseUrl);
        String defaultBaseUrl = defaultBaseUrl(provider, channel);
        String baseUrl = LLMConfigUtil.normalizeBaseUrl(
                configuredBaseUrl == null || configuredBaseUrl.isBlank()
                        ? defaultBaseUrl : configuredBaseUrl);

        return switch (provider) {
            case KIMI -> kimi(channel, baseUrl);
            case DEEPSEEK -> new AiProviderProfile(
                    provider, "default", "DeepSeek", baseUrl, 0.3,
                    TokenLimitParameter.MAX_TOKENS, 4096,
                    true, true, true, true, false);
            case GLM -> new AiProviderProfile(
                    provider, channel, "GLM", baseUrl, 0.6,
                    TokenLimitParameter.MAX_TOKENS, 4096,
                    true, true, true, true, true);
            case MINIMAX -> new AiProviderProfile(
                    provider, channel, "MiniMax", baseUrl, 1.0,
                    TokenLimitParameter.MAX_COMPLETION_TOKENS, 2048,
                    true, true, false, true, false);
            case MIMO -> new AiProviderProfile(
                    provider, channel, "MiMo", baseUrl, null,
                    TokenLimitParameter.MAX_COMPLETION_TOKENS, 4096,
                    true, true, true, true, true);
            case OPENAI -> new AiProviderProfile(
                    provider, "default", "OpenAI", baseUrl, 0.3,
                    TokenLimitParameter.MAX_COMPLETION_TOKENS, 4096,
                    false, true, true, true, true);
        };
    }

    public static String defaultBaseUrl(AiProvider provider, String channel) {
        return switch (provider) {
            case KIMI -> "platform".equals(channel)
                    ? "https://api.moonshot.cn/v1"
                    : "https://api.kimi.com/coding/v1";
            case DEEPSEEK -> "https://api.deepseek.com/v1";
            case GLM -> "coding".equals(channel)
                    ? "https://open.bigmodel.cn/api/coding/paas/v4"
                    : "https://open.bigmodel.cn/api/paas/v4";
            case MINIMAX -> "https://api.minimaxi.com/v1";
            case MIMO -> "token_plan".equals(channel)
                    ? "https://token-plan-cn.xiaomimimo.com/v1"
                    : "https://api.xiaomimimo.com/v1";
            case OPENAI -> "https://api.openai.com/v1";
        };
    }

    private static AiProviderProfile kimi(String channel, String baseUrl) {
        boolean coding = "coding".equals(channel);
        return new AiProviderProfile(
                AiProvider.KIMI, channel, coding ? "Kimi Coding" : "Kimi 开放平台",
                baseUrl, null, TokenLimitParameter.MAX_COMPLETION_TOKENS, 4096,
                true, true, true, true, true);
    }

    private static String normalizeChannel(AiProvider provider,
                                           String configuredChannel,
                                           String baseUrl) {
        String requested = configuredChannel == null
                ? "" : configuredChannel.trim().toLowerCase(Locale.ROOT);
        String base = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        return switch (provider) {
            case KIMI -> Set.of("coding", "platform").contains(requested)
                    ? requested : (base.contains("/coding/") ? "coding" : "platform");
            case GLM -> Set.of("standard", "coding").contains(requested)
                    ? requested : (base.contains("/coding/") ? "coding" : "standard");
            case MINIMAX -> Set.of("payg", "token_plan").contains(requested)
                    ? requested : "payg";
            case MIMO -> Set.of("payg", "token_plan").contains(requested)
                    ? requested : (base.contains("token-plan") ? "token_plan" : "payg");
            default -> "default";
        };
    }
}
