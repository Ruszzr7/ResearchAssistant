package com.research.assistant.service.source;

import com.research.assistant.service.IeeeXploreFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * IEEE Xplore 文献来源适配器。
 */
@Component
public class IeeeXploreSource implements LiteratureSource {

    private static final Logger log = LoggerFactory.getLogger(IeeeXploreSource.class);

    private final IeeeXploreFetcher ieeeXploreFetcher;

    public IeeeXploreSource(IeeeXploreFetcher ieeeXploreFetcher) {
        this.ieeeXploreFetcher = ieeeXploreFetcher;
    }

    @Override
    public String sourceName() {
        return "IEEE Xplore";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 100));
            List<Map<String, Object>> results = ieeeXploreFetcher.search(query, limit);
            return results.stream().map(IeeeXploreSource::toCandidate).toList();
        } catch (Exception e) {
            log.warn("IEEE Xplore 来源搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    static LiteratureCandidate toCandidate(Map<String, Object> r) {
        String externalId = String.valueOf(r.getOrDefault("paperId", ""));
        String sourceUrl = String.valueOf(r.getOrDefault("sourceUrl", ""));
        String pdfUrl = String.valueOf(r.getOrDefault("pdfUrl", ""));
        return new LiteratureCandidate(
                String.valueOf(r.getOrDefault("title", "")),
                String.valueOf(r.getOrDefault("authors", "")),
                String.valueOf(r.getOrDefault("published", "")),
                String.valueOf(r.getOrDefault("summary", "")),
                "",
                String.valueOf(r.getOrDefault("doi", "")),
                sourceUrl,
                pdfUrl,
                "IEEE Xplore",
                externalId
        );
    }
}
