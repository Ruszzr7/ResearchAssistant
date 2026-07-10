package com.research.assistant.service.source;

import com.research.assistant.service.IeeeXploreFetcher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IeeeXploreSourceTest {

    @Test
    void shouldReturnCandidatesFromFetcher() {
        IeeeXploreFetcher fetcher = mock(IeeeXploreFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenReturn(List.of(article("IEEE Paper", "2023", "12345")));
        IeeeXploreSource source = new IeeeXploreSource(fetcher);

        List<LiteratureCandidate> result = source.search("neural", 10);

        assertEquals(1, result.size());
        assertEquals("IEEE Paper", result.get(0).title());
        assertEquals("2023", result.get(0).year());
        assertEquals("IEEE Xplore", result.get(0).source());
        assertEquals("12345", result.get(0).externalId());
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        IeeeXploreSource source = new IeeeXploreSource(mock(IeeeXploreFetcher.class));
        assertTrue(source.search("  ", 10).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenFetcherFails() {
        IeeeXploreFetcher fetcher = mock(IeeeXploreFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenThrow(new RuntimeException("timeout"));
        IeeeXploreSource source = new IeeeXploreSource(fetcher);

        assertTrue(source.search("query", 10).isEmpty());
    }

    private Map<String, Object> article(String title, String year, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("published", year);
        m.put("paperId", id);
        m.put("sourceUrl", "https://ieeexplore.ieee.org/document/" + id);
        m.put("pdfUrl", "");
        m.put("doi", "10.1109/" + id);
        m.put("authors", "C D");
        m.put("summary", "abstract");
        m.put("source", "IEEE Xplore");
        return m;
    }
}
