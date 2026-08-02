package com.research.assistant.service.ai.provider;

/** Provider-specific policy layered on the shared OpenAI-compatible transport. */
public record AiProviderProfile(
        AiProvider provider,
        String channel,
        String displayName,
        String baseUrl,
        Double temperature,
        TokenLimitParameter tokenLimitParameter,
        int defaultMaxOutputTokens,
        boolean manualStreaming,
        boolean nativeStructuredOutput,
        boolean jsonResponseFormat,
        boolean reasoning,
        boolean vision
) {
    public String providerValue() {
        return provider.settingValue();
    }
}
