package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AiModelListRequest;
import com.research.assistant.dto.AiModelListResult;
import com.research.assistant.service.security.SettingsPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Queries the standard OpenAI-compatible model catalog without persisting form values. */
@Service
public class AiModelCatalogService {

    private static final int MAX_MODELS = 500;
    private static final int MAX_MODEL_ID_LENGTH = 200;

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;
    private final HttpClient httpClient;

    @Autowired
    public AiModelCatalogService(ObjectMapper objectMapper, SettingsService settingsService) {
        this(objectMapper, settingsService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    AiModelCatalogService(ObjectMapper objectMapper,
                          SettingsService settingsService,
                          HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
        this.httpClient = httpClient;
    }

    public AiModelListResult list(AiModelListRequest request) {
        URI endpoint = modelsEndpoint(request.baseUrl());
        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("请先填写 API Key");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelCatalogException("模型服务拒绝了查询", response.statusCode());
            }
            List<String> models = parseModels(response.body());
            if (models.isEmpty()) {
                throw new ModelCatalogException("模型服务未返回可用模型", response.statusCode());
            }
            return new AiModelListResult(models);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelCatalogException("查询可用模型被中断", 0, exception);
        } catch (ModelCatalogException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelCatalogException("无法读取模型列表", 0, exception);
        }
    }

    private URI modelsEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("请先填写 Base URL");
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("/chat/completions", "/responses", "/models")) {
            if (!lower.endsWith(suffix)) continue;
            normalized = normalized.substring(0, normalized.length() - suffix.length());
            if ("/models".equals(suffix)) normalized += "/models";
            break;
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith("/models")) normalized += "/models";
        URI endpoint;
        try {
            endpoint = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Base URL 格式无效");
        }
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Base URL 只允许使用有效的 http 或 https 地址");
        }
        return endpoint;
    }

    private String resolveApiKey(String value) {
        String supplied = value == null ? "" : value.trim();
        if (supplied.isBlank() || SettingsPolicy.isMaskedValue(supplied)) {
            return settingsService.getValue("api_key");
        }
        return supplied;
    }

    private List<String> parseModels(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body == null ? "" : body);
        JsonNode values = root.path("data");
        if (!values.isArray()) values = root.path("models");
        if (!values.isArray()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode item : values) {
            String id = item.isTextual() ? item.asText() : item.path("id").asText("");
            if (id.isBlank() && item.isObject()) id = item.path("name").asText("");
            id = id.trim();
            if (id.isBlank() || id.length() > MAX_MODEL_ID_LENGTH) continue;
            unique.add(id);
            if (unique.size() >= MAX_MODELS) break;
        }
        List<String> result = new ArrayList<>(unique);
        result.sort(Comparator.comparing(value -> value.toLowerCase(Locale.ROOT)));
        return List.copyOf(result);
    }

    public static class ModelCatalogException extends RuntimeException {
        private final int upstreamStatus;

        public ModelCatalogException(String message, int upstreamStatus) {
            super(message);
            this.upstreamStatus = upstreamStatus;
        }

        public ModelCatalogException(String message, int upstreamStatus, Throwable cause) {
            super(message, cause);
            this.upstreamStatus = upstreamStatus;
        }

        public int upstreamStatus() {
            return upstreamStatus;
        }
    }
}
