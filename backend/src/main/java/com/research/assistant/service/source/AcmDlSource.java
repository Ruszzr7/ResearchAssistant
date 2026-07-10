package com.research.assistant.service.source;

import com.research.assistant.service.AcmDlFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ACM DL 文献来源适配器。
 */
@Component
public class AcmDlSource implements LiteratureSource {

    private static final Logger log = LoggerFactory.getLogger(AcmDlSource.class);

    private final AcmDlFetcher acmDlFetcher;

    public AcmDlSource(AcmDlFetcher acmDlFetcher) {
        this.acmDlFetcher = acmDlFetcher;
    }

    @Override
    public String sourceName() {
        return "ACM DL";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> results = acmDlFetcher.search(query, maxResults);
            return results.stream().map(AcmDlSource::toCandidate).toList();
        } catch (Exception e) {
            log.warn("ACM DL 来源搜索失败 query={}: {}", query, e.getMessage());
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
                "ACM DL",
                externalId
        );
    }
}
