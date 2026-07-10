package com.research.assistant.service.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.SemanticScholarFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CitationNetworkExpansionServiceTest {

    private SemanticScholarFetcher fetcher;
    private PaperMapper paperMapper;
    private LiteratureSearchService literatureSearchService;
    private CitationNetworkExpansionService service;

    @BeforeEach
    void setUp() {
        fetcher = mock(SemanticScholarFetcher.class);
        paperMapper = mock(PaperMapper.class);
        literatureSearchService = mock(LiteratureSearchService.class);
        service = new CitationNetworkExpansionService(fetcher, paperMapper, literatureSearchService);
    }

    @Test
    void expandByLocalPaperIdShouldUseStoredS2Id() {
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setSemanticScholarId("s2id1");
        when(paperMapper.selectById(1L)).thenReturn(paper);

        LiteratureCandidate candidate = candidate("s2id2", "Forward Paper", "2024");
        when(fetcher.fetchCitations("s2id1", 3)).thenReturn(List.of(map(candidate)));
        when(literatureSearchService.deduplicate(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LiteratureCandidate> result = service.expandByLocalPaperId(1L, List.of("forward"), 3);

        assertEquals(1, result.size());
        assertEquals("Forward Paper", result.get(0).title());
        verify(fetcher).fetchCitations("s2id1", 3);
    }

    @Test
    void expandByS2IdShouldCombineDirections() {
        LiteratureCandidate forward = candidate("s2id2", "Forward", "2024");
        LiteratureCandidate backward = candidate("s2id3", "Backward", "2020");
        LiteratureCandidate author = candidate("s2id4", "Author", "2022");

        when(fetcher.fetchCitations("s2id1", 3)).thenReturn(List.of(map(forward)));
        when(fetcher.fetchReferences("s2id1", 3)).thenReturn(List.of(map(backward)));
        when(fetcher.fetchPaperAuthorIds("s2id1")).thenReturn(List.of("a1"));
        when(fetcher.fetchAuthorPapers("a1", 3)).thenReturn(List.of(map(author)));
        when(literatureSearchService.deduplicate(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LiteratureCandidate> result = service.expandByS2Id("s2id1",
                List.of("forward", "backward", "author"), 9);

        assertEquals(3, result.size());
    }

    @Test
    void shouldReturnEmptyWhenNoS2IdStored() {
        Paper paper = new Paper();
        paper.setId(2L);
        when(paperMapper.selectById(2L)).thenReturn(paper);

        List<LiteratureCandidate> result = service.expandByLocalPaperId(2L, List.of("forward"), 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldDeduplicateMergedResults() {
        LiteratureCandidate c1 = candidate("s2id2", "Paper", "2024");
        LiteratureCandidate c2 = candidate("s2id2", "Paper", "2024");
        when(fetcher.fetchCitations("s2id1", 5)).thenReturn(List.of(map(c1)));
        when(fetcher.fetchReferences("s2id1", 5)).thenReturn(List.of(map(c2)));
        when(literatureSearchService.deduplicate(anyList())).thenReturn(List.of(c1));

        List<LiteratureCandidate> result = service.expandByS2Id("s2id1",
                List.of("forward", "backward"), 10);

        assertEquals(1, result.size());
        verify(literatureSearchService).deduplicate(anyList());
    }

    @Test
    void shouldUseDefaultDirectionsWhenEmpty() {
        when(fetcher.fetchCitations(any(), anyInt())).thenReturn(List.of());
        when(fetcher.fetchReferences(any(), anyInt())).thenReturn(List.of());
        when(fetcher.fetchPaperAuthorIds(any())).thenReturn(List.of());
        when(literatureSearchService.deduplicate(anyList())).thenReturn(List.of());

        service.expandByS2Id("s2id1", null, 9);

        verify(fetcher).fetchCitations("s2id1", 3);
        verify(fetcher).fetchReferences("s2id1", 3);
        verify(fetcher).fetchPaperAuthorIds("s2id1");
    }

    private LiteratureCandidate candidate(String externalId, String title, String year) {
        return new LiteratureCandidate(title, "A Author", year, "summary", "", "",
                "https://example.org/" + externalId, "", "Semantic Scholar", externalId);
    }

    private Map<String, Object> map(LiteratureCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paperId", c.externalId());
        m.put("title", c.title());
        m.put("authors", c.authors());
        m.put("published", c.year());
        m.put("summary", c.summary());
        m.put("sourceUrl", c.sourceUrl());
        m.put("pdfUrl", c.pdfUrl());
        m.put("source", c.source());
        return m;
    }
}
