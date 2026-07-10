package com.research.assistant.service.source;

import com.research.assistant.service.OpenAlexFetcher;
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

class OpenAlexSourceTest {

    @Test
    void shouldReturnCandidatesFromFetcher() {
        OpenAlexFetcher fetcher = mock(OpenAlexFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenReturn(List.of(work("OpenAlex Work", "2024", "W1")));
        OpenAlexSource source = new OpenAlexSource(fetcher);

        List<LiteratureCandidate> result = source.search("transformer", 10);

        assertEquals(1, result.size());
        assertEquals("OpenAlex Work", result.get(0).title());
        assertEquals("2024", result.get(0).year());
        assertEquals("OpenAlex", result.get(0).source());
        assertEquals("W1", result.get(0).externalId());
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        OpenAlexSource source = new OpenAlexSource(mock(OpenAlexFetcher.class));
        assertTrue(source.search("", 10).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenFetcherFails() {
        OpenAlexFetcher fetcher = mock(OpenAlexFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenThrow(new RuntimeException("network"));
        OpenAlexSource source = new OpenAlexSource(fetcher);

        assertTrue(source.search("query", 10).isEmpty());
    }

    private Map<String, Object> work(String title, String year, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("published", year);
        m.put("paperId", id);
        m.put("sourceUrl", "https://openalex.org/" + id);
        m.put("pdfUrl", "");
        m.put("doi", "");
        m.put("authors", "A B");
        m.put("summary", "");
        m.put("source", "OpenAlex");
        return m;
    }
}
