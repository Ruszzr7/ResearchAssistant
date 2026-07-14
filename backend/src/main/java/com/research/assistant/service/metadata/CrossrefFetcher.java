package com.research.assistant.service.metadata;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crossref API 客户端 —— 通过 DOI 获取论文元数据。
 * <p>
 * 文档：https://api.crossref.org/swagger-ui/index.html
 */
@Component
public class CrossrefFetcher {

    private static final Logger log = LoggerFactory.getLogger(CrossrefFetcher.class);

    private static final String CROSSREF_BASE = "https://api.crossref.org/works/";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CrossrefFetcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 根据 DOI 查询元数据。
     *
     * @param doi DOI，如 10.1038/nature14539
     * @return 元数据 map；key 包括 title, source, year, authors, doi, sourceUrl, abstractText
     * @throws Exception 查询失败或解析失败
     */
    public Map<String, String> fetch(String doi) throws Exception {
        String url = CROSSREF_BASE + URLEncoder.encode(doi, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ResearchAssistant/1.0 (mailto:research@assistant.local)")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Crossref API 返回 HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode message = root.path("message");
        if (message.isMissingNode()) {
            throw new RuntimeException("Crossref 响应缺少 message 字段");
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("doi", doi);
        meta.put("title", extractFirstText(message.path("title")));
        meta.put("source", extractSource(message));
        meta.put("year", extractYear(message));
        meta.put("authors", extractAuthors(message.path("author")));
        meta.put("sourceUrl", message.path("URL").asText(""));
        meta.put("abstractText", cleanAbstract(message.path("abstract").asText("")));
        return meta;
    }

    private String extractSource(JsonNode message) {
        String source = extractFirstText(message.path("container-title"));
        if (source.isBlank()) {
            source = extractFirstText(message.path("short-container-title"));
        }
        if (source.isBlank()) {
            source = message.path("event").path("name").asText("");
        }
        return source;
    }

    private String extractFirstText(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).asText("");
        }
        return node.asText("");
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

    private String cleanAbstract(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 3000 ? cleaned.substring(0, 3000) : cleaned;
    }
}
