package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACM DL API 查询器。
 * <p>
 * ACM Digital Library 的公开 API 访问受限，通常需要机构订阅或特殊授权。
 * 本实现通过可配置的 {@code acm_dl_api_url} + {@code acm_dl_api_key} 接入；
 * 未配置时安全返回空列表，不影响其他来源。
 */
@Component
public class AcmDlFetcher {

    private static final Logger log = LoggerFactory.getLogger(AcmDlFetcher.class);

    private static final String API_URL_SETTING = "acm_dl_api_url";
    private static final String API_KEY_SETTING = "acm_dl_api_key";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SettingsService settingsService;

    @Autowired
    public AcmDlFetcher(ObjectMapper objectMapper, SettingsService settingsService) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), settingsService);
    }

    AcmDlFetcher(ObjectMapper objectMapper, HttpClient httpClient, SettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.settingsService = settingsService;
    }

    /**
     * 按关键词搜索 ACM DL。
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回数
     * @return 论文列表；未配置 API URL/Key 时返回空列表
     */
    public List<Map<String, Object>> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String apiUrl = settingsService.getValue(API_URL_SETTING);
        String apiKey = settingsService.getValue(API_KEY_SETTING);
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.debug("ACM DL API URL/Key 未配置，跳过该来源");
            return List.of();
        }
        try {
            String url = apiUrl
                    + (apiUrl.contains("?") ? "&" : "?")
                    + "query=" + encode(query)
                    + "&limit=" + maxResults;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("ACM DL API 返回 HTTP {}", response.statusCode());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> papers = new ArrayList<>();
            for (JsonNode item : results) {
                papers.add(normalizeItem(item));
            }
            return papers;
        } catch (Exception e) {
            log.warn("ACM DL 搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> normalizeItem(JsonNode item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("paperId", text(item, "id"));
        map.put("title", text(item, "title"));
        map.put("summary", text(item, "abstract"));
        map.put("published", text(item, "year"));
        map.put("sourceUrl", text(item, "url"));
        map.put("source", "ACM DL");
        map.put("authors", text(item, "authors"));
        map.put("doi", text(item, "doi"));
        map.put("arxivId", "");
        map.put("pdfUrl", text(item, "pdfUrl"));
        return map;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
