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
 * IEEE Xplore API 查询器。
 * <p>
 * IEEE Xplore 需要机构/个人 API Key，未配置时返回空列表。
 * 文档：https://developer.ieee.org/
 */
@Component
public class IeeeXploreFetcher {

    private static final Logger log = LoggerFactory.getLogger(IeeeXploreFetcher.class);

    private static final String API_BASE = "https://ieeexploreapi.ieee.org/api/v1/search/articles";
    private static final String API_KEY_SETTING = "ieee_xplore_api_key";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SettingsService settingsService;

    @Autowired
    public IeeeXploreFetcher(ObjectMapper objectMapper, SettingsService settingsService) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), settingsService);
    }

    IeeeXploreFetcher(ObjectMapper objectMapper, HttpClient httpClient, SettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.settingsService = settingsService;
    }

    /**
     * 按关键词搜索 IEEE Xplore。
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回数（上限 100）
     * @return 论文列表；未配置 API Key 时返回空列表
     */
    public List<Map<String, Object>> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String apiKey = settingsService.getValue(API_KEY_SETTING);
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("IEEE Xplore API Key 未配置，跳过该来源");
            return List.of();
        }
        try {
            String url = API_BASE
                    + "?querytext=" + encode(query)
                    + "&format=json"
                    + "&max_records=" + Math.min(maxResults, 100)
                    + "&apikey=" + encode(apiKey);
            HttpResponse<String> response = sendRequest(url);
            if (response.statusCode() != 200) {
                log.warn("IEEE Xplore API 返回 HTTP {}", response.statusCode());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode articles = root.get("articles");
            if (articles == null || !articles.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> papers = new ArrayList<>();
            for (JsonNode article : articles) {
                papers.add(normalizeArticle(article));
            }
            return papers;
        } catch (Exception e) {
            log.warn("IEEE Xplore 搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> normalizeArticle(JsonNode article) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("paperId", text(article, "article_number"));
        item.put("title", text(article, "title"));
        item.put("summary", text(article, "abstract"));
        item.put("published", text(article, "publication_year"));
        item.put("sourceUrl", text(article, "html_url"));
        item.put("source", "IEEE Xplore");
        item.put("authors", extractAuthors(article.get("authors")));
        item.put("doi", text(article, "doi"));
        item.put("arxivId", "");
        item.put("pdfUrl", text(article, "pdf_url"));
        return item;
    }

    private String extractAuthors(JsonNode authorsNode) {
        if (authorsNode == null || !authorsNode.isArray() || authorsNode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode author : authorsNode) {
            String name = text(author, "full_name");
            if (!name.isBlank()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(name);
            }
        }
        return sb.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private HttpResponse<String> sendRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
