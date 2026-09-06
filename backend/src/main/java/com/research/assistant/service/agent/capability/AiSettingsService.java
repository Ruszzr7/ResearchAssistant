package com.research.assistant.service.agent.capability;

import com.research.assistant.service.SettingsService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class AiSettingsService {
    private final SettingsService settings;

    public AiSettingsService(SettingsService settings) {
        this.settings = settings;
    }

    public AiSettings resolve() {
        return resolveValues(settings.getValue("base_url"), settings.getValue("api_key"),
                settings.getValue("model"), settings.getValue("ai_provider"),
                settings.getValue("ai_channel"));
    }

    /** Resolve one-shot values for capability testing without persisting them. */
    public AiSettings resolveDraft(String baseUrl, String model, String apiKey) {
        String effectiveKey = apiKey == null || apiKey.isBlank()
                ? settings.getValue("api_key") : apiKey;
        return resolveValues(baseUrl, effectiveKey, model, settings.getValue("ai_provider"),
                settings.getValue("ai_channel"));
    }

    private AiSettings resolveValues(String baseUrl, String apiKey, String model,
                                     String configuredProvider, String configuredChannel) {
        baseUrl = requiredValue(baseUrl, "Base URL");
        apiKey = requiredValue(apiKey, "API Key");
        model = requiredValue(model, "模型");
        String transport = isGeminiNativeUrl(baseUrl) ? "GEMINI_NATIVE" : "OPENAI_COMPATIBLE";
        String provider = "GEMINI_NATIVE".equals(transport) ? "gemini"
                : blankToNull(configuredProvider);
        String channel = configuredChannel == null || configuredChannel.isBlank()
                ? "default" : configuredChannel.trim().toLowerCase(java.util.Locale.ROOT);
        String signature = sha256(transport + "\0" + provider + "\0" + channel + "\0"
                + baseUrl + "\0" + model + "\0" + apiKey);
        return new AiSettings(transport, provider, channel, baseUrl, apiKey, model, signature);
    }

    private static String requiredValue(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " is not configured");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isGeminiNativeUrl(String baseUrl) {
        String normalized = baseUrl.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("generativelanguage.googleapis.com")
                || normalized.matches(".*/v1(beta|alpha)(/.*)?$");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
