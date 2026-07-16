package com.research.assistant.service.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.reliability.ExternalCallPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** DeepL adapter. Secrets are read at call time and never included in logs or response bodies. */
@Service
public class DeepLTranslationProvider implements TranslationProvider {

    static final String FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    static final String PRO_ENDPOINT = "https://api.deepl.com/v2/translate";

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final DeepLHttpTransport transport;
    private final ExternalCallPolicy externalCallPolicy;

    public DeepLTranslationProvider(SettingsService settingsService,
                                    ObjectMapper objectMapper,
                                    DeepLHttpTransport transport,
                                    ExternalCallPolicy externalCallPolicy) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.externalCallPolicy = externalCallPolicy;
    }

    @Override
    public String id() {
        return "deepl";
    }

    @Override
    public boolean configured() {
        return !value("deepl_auth_key").isBlank();
    }

    @Override
    public List<ProviderTranslation> translate(List<String> texts,
                                               TranslationLanguage sourceLanguage,
                                               TranslationLanguage targetLanguage) {
        String authKey = value("deepl_auth_key");
        if (authKey.isBlank()) {
            throw new TranslationException("PROVIDER_NOT_CONFIGURED",
                    "DeepL 翻译服务尚未配置", HttpStatus.SERVICE_UNAVAILABLE, false);
        }
        if (texts == null || texts.isEmpty() || texts.size() > 50) {
            throw new IllegalArgumentException("DeepL accepts 1 to 50 texts");
        }

        URI endpoint = endpoint(authKey);
        String requestBody = requestBody(texts, sourceLanguage, targetLanguage);
        DeepLCallResult call = externalCallPolicy.execute(
                "deepl_translate",
                () -> callOnce(endpoint, authKey, requestBody),
                () -> DeepLCallResult.failure("PROVIDER_TEMPORARY_FAILURE",
                        "翻译服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true));
        if (!call.success()) {
            throw new TranslationException(call.errorCode(), call.errorMessage(),
                    call.status(), call.retryable());
        }
        return call.translations();
    }

    private DeepLCallResult callOnce(URI endpoint, String authKey, String requestBody) throws Exception {
        DeepLHttpTransport.Response response = transport.post(
                endpoint, "DeepL-Auth-Key " + authKey, requestBody, externalCallPolicy.timeout());
        int status = response.statusCode();
        if (status == 200) return parseSuccess(response.body());
        if (status == 403) {
            return DeepLCallResult.failure("INVALID_CREDENTIALS",
                    "DeepL 凭据无效，请检查配置", HttpStatus.SERVICE_UNAVAILABLE, false);
        }
        if (status == 456) {
            return DeepLCallResult.failure("QUOTA_EXCEEDED",
                    "DeepL 翻译额度已用完", HttpStatus.PAYMENT_REQUIRED, false);
        }
        if (status == 429 || status >= 500) throw new RetryableProviderException();
        return DeepLCallResult.failure("PROVIDER_REQUEST_REJECTED",
                "翻译服务拒绝了本次请求", HttpStatus.BAD_GATEWAY, false);
    }

    private DeepLCallResult parseSuccess(String body) {
        try {
            JsonNode translations = objectMapper.readTree(body).path("translations");
            if (!translations.isArray() || translations.isEmpty()) {
                throw new IllegalArgumentException("missing translations");
            }
            List<ProviderTranslation> result = java.util.stream.StreamSupport
                    .stream(translations.spliterator(), false)
                    .map(node -> new ProviderTranslation(
                            node.path("text").asText(""),
                            TranslationLanguage.normalizeDetected(
                                    node.path("detected_source_language").asText(""))))
                    .toList();
            if (result.stream().anyMatch(item -> item.text().isBlank())) {
                throw new IllegalArgumentException("blank translation");
            }
            return DeepLCallResult.success(result);
        } catch (Exception e) {
            return DeepLCallResult.failure("INVALID_PROVIDER_RESPONSE",
                    "翻译服务返回了无法解析的内容，请重试", HttpStatus.BAD_GATEWAY, true);
        }
    }

    private String requestBody(List<String> texts,
                               TranslationLanguage sourceLanguage,
                               TranslationLanguage targetLanguage) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", texts);
            payload.put("target_lang", targetLanguage.deepLTargetCode());
            if (sourceLanguage != TranslationLanguage.AUTO) {
                payload.put("source_lang", sourceLanguage.deepLSourceCode());
            }
            payload.put("preserve_formatting", true);
            payload.put("tag_handling", "xml");
            payload.put("tag_handling_version", "v2");
            payload.put("ignore_tags", List.of("keep"));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build translation request", e);
        }
    }

    private URI endpoint(String authKey) {
        String configured = value("deepl_api_base_url");
        String raw = configured.isBlank()
                ? (authKey.endsWith(":fx") ? FREE_ENDPOINT : PRO_ENDPOINT)
                : normalizeEndpoint(configured);
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new TranslationException("INVALID_ENDPOINT", "DeepL 服务地址配置无效",
                    HttpStatus.SERVICE_UNAVAILABLE, false);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new TranslationException("INSECURE_ENDPOINT", "DeepL 服务地址必须使用 HTTPS",
                    HttpStatus.SERVICE_UNAVAILABLE, false);
        }
        return uri;
    }

    private String normalizeEndpoint(String baseUrl) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/v2/translate")) return normalized;
        if (normalized.endsWith("/v2")) return normalized + "/translate";
        return normalized + "/v2/translate";
    }

    private String value(String key) {
        String result = settingsService.getValue(key);
        return result == null ? "" : result.trim();
    }

    private record DeepLCallResult(
            boolean success,
            List<ProviderTranslation> translations,
            String errorCode,
            String errorMessage,
            HttpStatus status,
            boolean retryable) {

        static DeepLCallResult success(List<ProviderTranslation> translations) {
            return new DeepLCallResult(true, List.copyOf(translations), null, null, HttpStatus.OK, false);
        }

        static DeepLCallResult failure(String code, String message, HttpStatus status, boolean retryable) {
            return new DeepLCallResult(false, List.of(), code, message, status, retryable);
        }
    }

    private static final class RetryableProviderException extends Exception {
    }
}
