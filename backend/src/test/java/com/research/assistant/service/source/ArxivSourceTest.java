package com.research.assistant.service.source;

import com.research.assistant.service.ArxivFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArxivSourceTest {

    private ArxivFetcher arxivFetcher;
    private ArxivSource arxivSource;

    @BeforeEach
    void setUp() {
        arxivFetcher = mock(ArxivFetcher.class);
        arxivSource = new ArxivSource(arxivFetcher);
    }

    @Test
    void shouldNormalizeArxivResults() throws Exception {
        Map<String, Object> paper = Map.of(
                "title", "Attention Is All You Need",
                "authors", "Vaswani et al",
                "published", "2017",
                "summary", "We propose Transformer",
                "arxivId", "1706.03762",
                "pdfUrl", "https://arxiv.org/pdf/1706.03762"
        );
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of(paper));

        List<LiteratureCandidate> result = arxivSource.search("transformer", 5);

        assertEquals(1, result.size());
        LiteratureCandidate c = result.get(0);
        assertEquals("Attention Is All You Need", c.title());
        assertEquals("1706.03762", c.arxivId());
        assertEquals("arXiv", c.source());
        assertTrue(c.sourceUrl().contains("1706.03762"));
    }

    @Test
    void shouldReturnEmptyOnBlankQuery() {
        assertTrue(arxivSource.search("  ", 5).isEmpty());
    }
}
