package com.research.assistant.service.source;

import com.research.assistant.service.SemanticScholarFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Semantic Scholar 文献来源适配器。
 */
@Component
public class SemanticScholarSource implements LiteratureSource {

    private static final Logger log = LoggerFactory.getLogger(SemanticScholarSource.class);

    private final SemanticScholarFetcher semanticScholarFetcher;

    public SemanticScholarSource(SemanticScholarFetcher semanticScholarFetcher) {
        this.semanticScholarFetcher = semanticScholarFetcher;
    }

    @Override
    public String sourceName() {
        return "Semantic Scholar";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 100));
            List<Map<String, Object>> results = semanticScholarFetcher.search(query, limit);
            return results.stream().map(SemanticScholarSource::toCandidate).toList();
        } catch (Exception e) {
            log.warn("Semantic Scholar 来源搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    static LiteratureCandidate toCandidate(Map<String, Object> r) {
        String externalId = String.valueOf(r.getOrDefault("paperId", ""));
        String arxivId = String.valueOf(r.getOrDefault("arxivId", ""));
        String sourceUrl = String.valueOf(r.getOrDefault("sourceUrl", ""));
        String pdfUrl = String.valueOf(r.getOrDefault("pdfUrl", ""));
        return new LiteratureCandidate(
                String.valueOf(r.getOrDefault("title", "")),
                String.valueOf(r.getOrDefault("authors", "")),
                String.valueOf(r.getOrDefault("published", "")),
                String.valueOf(r.getOrDefault("summary", "")),
                arxivId,
                "",
                sourceUrl.isBlank() && !arxivId.isBlank() ? "https://arxiv.org/abs/" + arxivId : sourceUrl,
                pdfUrl,
                "Semantic Scholar",
                externalId
        );
    }
}
