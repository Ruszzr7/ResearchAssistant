package com.research.assistant.service.ai.provider;

import com.research.assistant.service.ai.LLMConfigUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderRegistryTest {

    @Test
    void resolvesAllSupportedProviderProfiles() {
        AiProviderProfile kimi = resolve(AiProvider.KIMI, "coding", null, "k3");
        assertThat(kimi.baseUrl()).isEqualTo("https://api.kimi.com/coding/v1");
        assertThat(kimi.temperature()).isNull();
        assertThat(kimi.manualStreaming()).isTrue();
        assertThat(kimi.vision()).isTrue();

        AiProviderProfile deepSeek = resolve(
                AiProvider.DEEPSEEK, "default", null, "deepseek-chat");
        assertThat(deepSeek.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(deepSeek.tokenLimitParameter()).isEqualTo(TokenLimitParameter.MAX_TOKENS);
        assertThat(deepSeek.vision()).isFalse();

        AiProviderProfile glm = resolve(AiProvider.GLM, "coding", null, "glm-4.5");
        assertThat(glm.baseUrl()).contains("/api/coding/paas/v4");
        assertThat(glm.vision()).isTrue();

        AiProviderProfile minimax = resolve(
                AiProvider.MINIMAX, "payg", null, "MiniMax-M2.1");
        assertThat(minimax.temperature()).isEqualTo(1.0);
        assertThat(minimax.defaultMaxOutputTokens()).isEqualTo(2048);
        assertThat(minimax.jsonResponseFormat()).isFalse();

        AiProviderProfile mimo = resolve(
                AiProvider.MIMO, "token_plan", null, "mimo-v2-flash");
        assertThat(mimo.baseUrl()).isEqualTo("https://token-plan-cn.xiaomimimo.com/v1");
        assertThat(mimo.temperature()).isNull();

        AiProviderProfile openAi = resolve(
                AiProvider.OPENAI, "default", null, "gpt-5-mini");
        assertThat(openAi.manualStreaming()).isFalse();
        assertThat(openAi.embedding()).isTrue();
    }

    @Test
    void preservesCustomCompatibleGatewayAndVersionPath() {
        AiProviderProfile profile = resolve(
                AiProvider.OPENAI, "default",
                "https://gateway.example.com/team/openai/v42/chat/completions/",
                "custom-model");

        assertThat(profile.baseUrl()).isEqualTo(
                "https://gateway.example.com/team/openai/v42");
        assertThat(LLMConfigUtil.chatCompletionsUrl(profile.baseUrl()))
                .isEqualTo("https://gateway.example.com/team/openai/v42/chat/completions");
    }

    @Test
    void infersLegacyProviderAndChannelWhenNewSettingsAreMissing() {
        AiProviderProfile profile = AiProviderRegistry.resolve(
                null, null, "https://api.kimi.com/coding/v1", "k3-256k");

        assertThat(profile.provider()).isEqualTo(AiProvider.KIMI);
        assertThat(profile.channel()).isEqualTo("coding");
    }

    private static AiProviderProfile resolve(AiProvider provider,
                                             String channel,
                                             String baseUrl,
                                             String model) {
        return AiProviderRegistry.resolve(
                provider.settingValue(), channel, baseUrl, model);
    }
}
