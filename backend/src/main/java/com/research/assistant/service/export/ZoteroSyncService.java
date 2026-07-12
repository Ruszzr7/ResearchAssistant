package com.research.assistant.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pushes paper metadata to the Zotero Web API without logging credentials or response bodies. */
@Service
public class ZoteroSyncService {

    private static final Logger log = LoggerFactory.getLogger(ZoteroSyncService.class);
    private static final String USER_ID_KEY = "zotero_user_id";
    private static final String API_KEY_KEY = "zotero_api_key";
    private static final String COLLECTION_KEY_KEY = "zotero_collection_key";

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public ZoteroSyncService(SettingsService settingsService, ObjectMapper objectMapper) {
        this(settingsService, objectMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public ZoteroSyncService(SettingsService settingsService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public int sync(List<Paper> papers) throws Exception {
        String userId = settingsService.getValue(USER_ID_KEY);
        String apiKey = settingsService.getValue(API_KEY_KEY);
        if (userId == null || userId.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Zotero 配置不完整");
        }
        String collectionKey = settingsService.getValue(COLLECTION_KEY_KEY);
        String url = "https://api.zotero.org/users/" + userId + "/items";
        List<Map<String, Object>> items = new ArrayList<>();
        for (Paper paper : papers) items.add(toZoteroItem(paper, collectionKey));

        String body = objectMapper.writeValueAsString(items);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Zotero-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Zotero sync failed status={}", response.statusCode());
            throw new RuntimeException("Zotero 同步失败: HTTP " + response.statusCode());
        }
        return items.size();
    }

    private Map<String, Object> toZoteroItem(Paper paper, String collectionKey) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemType", itemType(paper));
        item.put("title", paper.getTitle());
        item.put("creators", creators(paper.getAuthors()));
        if (paper.getYear() != null) item.put("date", String.valueOf(paper.getYear()));
        if (paper.getDoi() != null && !paper.getDoi().isBlank()) item.put("DOI", paper.getDoi());
        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) item.put("extra", "arXiv: " + paper.getArxivId());
        if (paper.getSourceUrl() != null && !paper.getSourceUrl().isBlank()) item.put("url", paper.getSourceUrl());
        if (paper.getSource() != null && !paper.getSource().isBlank()) {
            String lower = paper.getSource().toLowerCase();
            item.put(lower.contains("conference") || lower.contains("proceedings") ? "proceedingsTitle" : "publicationTitle", paper.getSource());
        }
        if (collectionKey != null && !collectionKey.isBlank()) item.put("collections", List.of(collectionKey));
        return item;
    }

    private String itemType(Paper paper) {
        String source = paper.getSource() == null ? "" : paper.getSource().toLowerCase();
        if (source.contains("conference") || source.contains("proceedings") || source.contains("symposium") || source.contains("workshop")) return "conferencePaper";
        if (source.contains("journal") || source.contains("letters") || source.contains("transactions") || source.contains("magazine")) return "journalArticle";
        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) return "journalArticle";
        return "document";
    }

    private List<Map<String, String>> creators(String authorsJson) {
        List<Map<String, String>> list = new ArrayList<>();
        if (authorsJson == null || authorsJson.isBlank()) return list;
        try {
            List<?> parsed = objectMapper.readValue(authorsJson, List.class);
            for (Object object : parsed) {
                if (!(object instanceof Map<?, ?> map)) continue;
                Object nameValue = map.get("name");
                String name = String.valueOf(nameValue == null ? "" : nameValue).trim();
                if (name.isBlank()) continue;
                String[] parts = name.split("\\s+");
                Map<String, String> creator = new LinkedHashMap<>();
                creator.put("creatorType", "author");
                if (parts.length > 1) {
                    creator.put("firstName", String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)));
                    creator.put("lastName", parts[parts.length - 1]);
                } else creator.put("lastName", name);
                list.add(creator);
            }
        } catch (Exception e) {
            log.debug("Zotero author parsing failed type={}", e.getClass().getSimpleName());
        }
        return list;
    }
}
