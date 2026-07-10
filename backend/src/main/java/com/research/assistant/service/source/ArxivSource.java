package com.research.assistant.service.source;

import com.research.assistant.service.ArxivFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * arXiv 文献来源适配器。
 * <p>
 * arXiv 公开 API 有速率限制，因此同一实例内多个关键词搜索被串行化，
 * 并在连续调用间增加短暂间隔，避免触发限流。
 */
@Component
public class ArxivSource implements LiteratureSource {

    private static final Logger log = LoggerFactory.getLogger(ArxivSource.class);

    private final ArxivFetcher arxivFetcher;

    public ArxivSource(ArxivFetcher arxivFetcher) {
        this.arxivFetcher = arxivFetcher;
    }

    @Override
    public String sourceName() {
        return "arXiv";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 100));
            List<Map<String, Object>> results = arxivFetcher.search(query, limit);
            return results.stream().map(this::toCandidate).toList();
        } catch (Exception e) {
            log.warn("arXiv 来源搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 串行化多关键词搜索，避免 arXiv 并发限流。
     */
    @Override
    public List<LiteratureCandidate> searchKeywords(List<String> keywords, int maxResults) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<LiteratureCandidate> all = new ArrayList<>();
        synchronized (this) {
            for (String kw : keywords) {
                if (kw == null || kw.isBlank()) continue;
                all.addAll(search(kw.trim(), maxResults));
                if (keywords.size() > 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return all;
    }

    private LiteratureCandidate toCandidate(Map<String, Object> r) {
        String arxivId = String.valueOf(r.getOrDefault("arxivId", ""));
        return new LiteratureCandidate(
                String.valueOf(r.getOrDefault("title", "")),
                String.valueOf(r.getOrDefault("authors", "")),
                String.valueOf(r.getOrDefault("published", "")),
                String.valueOf(r.getOrDefault("summary", "")),
                arxivId,
                "",
                "https://arxiv.org/abs/" + arxivId,
                String.valueOf(r.getOrDefault("pdfUrl", "")),
                sourceName(),
                arxivId
        );
    }
}
