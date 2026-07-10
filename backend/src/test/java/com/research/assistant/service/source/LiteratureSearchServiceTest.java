package com.research.assistant.service.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteratureSearchServiceTest {

    private LiteratureSearchService service;

    @BeforeEach
    void setUp() {
        // 同步执行器，让测试不依赖线程池
        TaskExecutor sync = Runnable::run;
        service = new LiteratureSearchService(List.of(), sync);
    }

    @Test
    void shouldDeduplicateByDoiAndMergeSources() {
        LiteratureCandidate a = candidate("Paper A", "2023", "doi:10.1/a", "", "arXiv");
        LiteratureCandidate b = candidate("Paper A", "2023", "doi:10.1/a", "", "Semantic Scholar");

        List<LiteratureCandidate> result = service.deduplicate(List.of(a, b));

        assertEquals(1, result.size());
        assertEquals("arXiv, Semantic Scholar", result.get(0).source());
    }

    @Test
    void shouldPreferCandidateWithSummaryAndRecentYear() {
        LiteratureCandidate oldNoSummary = candidate("Old", "2020", "", "", "arXiv");
        LiteratureCandidate recentWithSummary = candidate("Recent", "2024", "", "has summary", "Semantic Scholar");

        List<LiteratureCandidate> result = service.deduplicate(List.of(oldNoSummary, recentWithSummary));

        assertEquals("Recent", result.get(0).title());
        assertEquals("Old", result.get(1).title());
    }

    @Test
    void shouldGenerateResultMapsWithReason() {
        LiteratureCandidate c = candidate("Transformer Survey", "2023", "", "Survey of transformers", "arXiv");
        List<Map<String, Object>> maps = service.toResultMaps(List.of(c), List.of("transformer", "survey"));

        assertEquals(1, maps.size());
        String reason = (String) maps.get(0).get("recommendReason");
        assertTrue(reason.contains("transformer"), reason);
        assertTrue(reason.contains("arXiv"), reason);
    }

    private LiteratureCandidate candidate(String title, String year, String doi, String summary, String source) {
        return new LiteratureCandidate(title, "", year, summary, "", doi, "", "", source, doi);
    }
}
