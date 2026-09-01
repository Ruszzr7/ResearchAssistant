package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
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
 * Semantic Scholar API 查询器 —— 用于补充 arXiv 之外的学术来源。
 * <p>
 * Semantic Scholar 提供免费公开 API，无需 Key 即可进行基础搜索；机构/高频使用可配置 x-api-key。
 * 文档：https://api.semanticscholar.org/api-docs/graph
 */
@Component
public class SemanticScholarFetcher {

    private static final String API_BASE = "https://api.semanticscholar.org/graph/v1";
    private static final String FIELDS = "paperId,title,authors,year,abstract,url,externalIds";
    private static final String API_KEY_SETTING = "semantic_scholar_api_key";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SettingsService settingsService;

    @Autowired
    public SemanticScholarFetcher(ObjectMapper objectMapper, SettingsService settingsService) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), settingsService);
    }

    SemanticScholarFetcher(ObjectMapper objectMapper, HttpClient httpClient, SettingsService settingsService) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.settingsService = settingsService;
    }

    /**
     * 按关键词搜索 Semantic Scholar 论文。
     *
     * @param query      搜索关键词（建议使用英文术语）
     * @param maxResults 最大返回数（1-100）
     * @return 论文列表，字段与 arXiv 结果对齐
     */
    public List<Map<String, Object>> search(String query, int maxResults) throws Exception {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String url = API_BASE + "/paper/search"
                + "?query=" + encode(query)
                + "&fields=" + FIELDS
                + "&limit=" + Math.min(maxResults, 100);

        return fetchPapers(url);
    }

    private List<Map<String, Object>> fetchPapers(String url) throws Exception {
        HttpResponse<String> response = sendRequest(url);
        if (response.statusCode() != 200) {
            throw new RuntimeException("Semantic Scholar API 返回 HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode paper : data) {
            if (paper != null && !paper.isNull()) {
                result.add(normalizePaper(paper));
            }
        }
        return result;
    }

    private HttpResponse<String> sendRequest(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        String apiKey = settingsService != null ? settingsService.getValue(API_KEY_SETTING) : null;
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("x-api-key", apiKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> normalizePaper(JsonNode paper) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("paperId", text(paper, "paperId"));
        item.put("title", text(paper, "title"));
        item.put("summary", text(paper, "abstract"));
        item.put("published", text(paper, "year"));
        item.put("sourceUrl", text(paper, "url"));
        item.put("source", "Semantic Scholar");
        item.put("authors", extractAuthors(paper.get("authors")));
        item.put("authorIds", extractAuthorIds(paper.get("authors")));

        JsonNode externalIds = paper.get("externalIds");
        if (externalIds != null && externalIds.has("ArXiv")) {
            String arxivId = externalIds.get("ArXiv").asText();
            item.put("arxivId", arxivId);
            item.put("pdfUrl", "https://arxiv.org/pdf/" + arxivId);
        }

        return item;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : "";
    }

    private String extractAuthors(JsonNode authorsNode) {
        if (authorsNode == null || !authorsNode.isArray() || authorsNode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode author : authorsNode) {
            String name = text(author, "name");
            if (!name.isBlank()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(name);
            }
        }
        return sb.toString();
    }

    private List<String> extractAuthorIds(JsonNode authorsNode) {
        List<String> ids = new ArrayList<>();
        if (authorsNode == null || !authorsNode.isArray()) {
            return ids;
        }
        for (JsonNode author : authorsNode) {
            String id = text(author, "authorId");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
