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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAlex API 查询器。
 * <p>
 * OpenAlex 是免费开放的学术图谱，无需 API Key，适合作为默认补充来源。
 * 文档：https://docs.openalex.org/
 */
@Component
public class OpenAlexFetcher {

    private static final Logger log = LoggerFactory.getLogger(OpenAlexFetcher.class);

    private static final String API_BASE = "https://api.openalex.org/works";
    private static final String SELECT_FIELDS = "id,title,publication_year,authorships,doi,open_access";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OpenAlexFetcher(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    OpenAlexFetcher(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 按关键词搜索 OpenAlex 论文。
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回数（上限 200）
     * @return 论文列表，字段与项目其他来源对齐
     */
    public List<Map<String, Object>> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String url = API_BASE
                    + "?search=" + encode(query)
                    + "&per-page=" + Math.min(maxResults, 200)
                    + "&select=" + SELECT_FIELDS;
            HttpResponse<String> response = sendRequest(url);
            if (response.statusCode() != 200) {
                log.warn("OpenAlex API 返回 HTTP {}", response.statusCode());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> papers = new ArrayList<>();
            for (JsonNode work : results) {
                papers.add(normalizeWork(work));
            }
            return papers;
        } catch (Exception e) {
            log.warn("OpenAlex 搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> normalizeWork(JsonNode work) {
        Map<String, Object> item = new LinkedHashMap<>();
        String openAlexId = text(work, "id");
        item.put("paperId", openAlexId);
        item.put("title", text(work, "title"));
        item.put("summary", "");
        item.put("published", text(work, "publication_year"));
        item.put("sourceUrl", sourceUrl(work, openAlexId));
        item.put("source", "OpenAlex");
        item.put("authors", extractAuthors(work.get("authorships")));
        item.put("doi", extractDoi(work.get("doi")));
        item.put("arxivId", "");
        item.put("pdfUrl", text(work.path("open_access"), "oa_url"));
        return item;
    }

    private String sourceUrl(JsonNode work, String openAlexId) {
        String doi = extractDoi(work.get("doi"));
        if (!doi.isBlank()) {
            return "https://doi.org/" + doi;
        }
        return openAlexId;
    }

    private String extractDoi(JsonNode doiNode) {
        if (doiNode == null || doiNode.isNull()) {
            return "";
        }
        String doi = doiNode.asText("");
        if (doi.startsWith("https://doi.org/")) {
            doi = doi.substring("https://doi.org/".length());
        }
        return doi;
    }

    private String extractAuthors(JsonNode authorships) {
        if (authorships == null || !authorships.isArray() || authorships.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode authorship : authorships) {
            JsonNode author = authorship.get("author");
            if (author == null || author.isNull()) {
                continue;
            }
            String name = text(author, "display_name");
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
