package com.research.assistant.service.agent.capability;

import com.research.assistant.service.SettingsService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class AiRoleSettingsService {
    private final SettingsService settings;

    public AiRoleSettingsService(SettingsService settings) { this.settings = settings; }

    public AiRoleSettings resolve(AiModelRole role) {
        String prefix = role == AiModelRole.CHAT ? "" : "document_";
        return resolveValues(role,
                settings.getValue(prefix + "base_url"),
                settings.getValue(prefix + "api_key"),
                settings.getValue(prefix + "model"));
    }

    /**
     * Resolve one-shot values for the capability test. A blank key means
     * "use the already persisted key"; URL and model always come from the
     * request. No value is written by this method.
     */
    public AiRoleSettings resolveDraft(AiModelRole role, String baseUrl, String model, String apiKey) {
        String prefix = role == AiModelRole.CHAT ? "" : "document_";
        String effectiveKey = apiKey == null || apiKey.isBlank()
                ? settings.getValue(prefix + "api_key") : apiKey;
        return resolveValues(role, baseUrl, effectiveKey, model);
    }

    private AiRoleSettings resolveValues(AiModelRole role, String baseUrl, String apiKey, String model) {
        baseUrl = requiredValue(baseUrl, "Base URL");
        apiKey = requiredValue(apiKey, "API Key");
        model = requiredValue(model, "model");
        String transport = role == AiModelRole.DOCUMENT && isGeminiNativeUrl(baseUrl)
                ? "GEMINI_NATIVE" : "OPENAI_COMPATIBLE";
        String provider = "GEMINI_NATIVE".equals(transport) ? "gemini" : "auto";
        String channel = "default";
        String signature = sha256(role + "\0" + transport + "\0" + provider + "\0" + channel + "\0"
                + baseUrl + "\0" + model + "\0" + apiKey);
        return new AiRoleSettings(role, transport, provider, channel, baseUrl, apiKey, model, signature);
    }

    private static String requiredValue(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " is not configured");
        return value.trim();
    }

    private static boolean isGeminiNativeUrl(String baseUrl) {
        String normalized = baseUrl.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("generativelanguage.googleapis.com")
                || normalized.matches(".*/v1(beta|alpha)(/.*)?$");
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
