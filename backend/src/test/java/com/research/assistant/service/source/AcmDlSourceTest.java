package com.research.assistant.service.source;

import com.research.assistant.service.AcmDlFetcher;
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

class AcmDlSourceTest {

    @Test
    void shouldReturnCandidatesFromFetcher() {
        AcmDlFetcher fetcher = mock(AcmDlFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenReturn(List.of(item("ACM Paper", "2022", "A1")));
        AcmDlSource source = new AcmDlSource(fetcher);

        List<LiteratureCandidate> result = source.search("compiler", 10);

        assertEquals(1, result.size());
        assertEquals("ACM Paper", result.get(0).title());
        assertEquals("2022", result.get(0).year());
        assertEquals("ACM DL", result.get(0).source());
        assertEquals("A1", result.get(0).externalId());
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        AcmDlSource source = new AcmDlSource(mock(AcmDlFetcher.class));
        assertTrue(source.search(null, 10).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenFetcherFails() {
        AcmDlFetcher fetcher = mock(AcmDlFetcher.class);
        when(fetcher.search(anyString(), anyInt())).thenThrow(new RuntimeException("timeout"));
        AcmDlSource source = new AcmDlSource(fetcher);

        assertTrue(source.search("query", 10).isEmpty());
    }

    private Map<String, Object> item(String title, String year, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("published", year);
        m.put("paperId", id);
        m.put("sourceUrl", "https://dl.acm.org/doi/" + id);
        m.put("pdfUrl", "");
        m.put("doi", "10.1145/" + id);
        m.put("authors", "E F");
        m.put("summary", "abstract");
        m.put("source", "ACM DL");
        return m;
    }
}
