package com.research.assistant.service.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;

/**
 * Crossref 文献来源适配器 —— 通过关键词搜索期刊/会议论文。
 * <p>
 * Crossref 免费 API  polite pool 要求提供 mailto，且多数记录没有摘要。
 * 本来源定位为「元数据补充源」：提供 DOI、期刊/会议、年份、作者信息。
 */
@Component
public class CrossrefSource implements LiteratureSource {

    private static final Logger log = LoggerFactory.getLogger(CrossrefSource.class);

    private static final String API_BASE = "https://api.crossref.org/works";
    private static final String USER_AGENT = "ResearchAssistant/1.0 (mailto:research@assistant.local)";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CrossrefSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String sourceName() {
        return "Crossref";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 20));
            String url = API_BASE
                    + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&rows=" + limit
                    + "&mailto=research@assistant.local";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Crossref API 返回 HTTP {}", response.statusCode());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.warn("Crossref 来源搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    private List<LiteratureCandidate> parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.path("message").path("items");
        if (!items.isArray()) {
            return List.of();
        }

        List<LiteratureCandidate> result = new ArrayList<>();
        for (JsonNode item : items) {
            String doi = text(item, "DOI");
            String title = firstText(item.path("title"));
            if (title.isBlank()) continue;

            result.add(new LiteratureCandidate(
                    title,
                    extractAuthors(item.path("author")),
                    extractYear(item),
                    "",
                    "",
                    doi,
                    text(item, "URL"),
                    "",
                    sourceName(),
                    doi
            ));
        }
        return result;
    }

    private String firstText(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).asText("");
        }
        return node.asText("");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText("") : "";
    }

    private String extractAuthors(JsonNode authorArray) {
        if (!authorArray.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode author : authorArray) {
            String given = author.path("given").asText("").trim();
            String family = author.path("family").asText("").trim();
            if (!given.isEmpty() && !family.isEmpty()) {
                names.add(given + " " + family);
            } else if (!family.isEmpty()) {
                names.add(family);
            } else if (!given.isEmpty()) {
                names.add(given);
            }
        }
        return String.join(", ", names);
    }

    private String extractYear(JsonNode message) {
        String[] paths = {"published-print", "published-online", "created"};
        for (String path : paths) {
            JsonNode dateParts = message.path(path).path("date-parts");
            if (dateParts.isArray() && !dateParts.isEmpty()) {
                JsonNode first = dateParts.get(0);
                if (first.isArray() && !first.isEmpty()) {
                    return first.get(0).asText("");
                }
            }
        }
        return "";
    }
}
