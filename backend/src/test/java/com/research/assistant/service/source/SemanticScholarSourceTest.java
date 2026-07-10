package com.research.assistant.service.source;

import com.research.assistant.service.SemanticScholarFetcher;
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

class SemanticScholarSourceTest {

    private SemanticScholarFetcher fetcher;
    private SemanticScholarSource source;

    @BeforeEach
    void setUp() {
        fetcher = mock(SemanticScholarFetcher.class);
        source = new SemanticScholarSource(fetcher);
    }

    @Test
    void shouldNormalizeSemanticScholarResults() throws Exception {
        Map<String, Object> paper = Map.of(
                "paperId", "s2pid123",
                "title", "BERT: Pre-training",
                "authors", "Devlin et al",
                "published", "2019",
                "summary", "We introduce BERT",
                "sourceUrl", "https://semanticscholar.org/paper/123",
                "source", "Semantic Scholar",
                "arxivId", "",
                "pdfUrl", ""
        );
        when(fetcher.search(anyString(), anyInt())).thenReturn(List.of(paper));

        List<LiteratureCandidate> result = source.search("bert", 5);

        assertEquals(1, result.size());
        LiteratureCandidate c = result.get(0);
        assertEquals("BERT: Pre-training", c.title());
        assertEquals("s2pid123", c.externalId());
        assertEquals("Semantic Scholar", c.source());
    }

    @Test
    void shouldUseArxivUrlWhenSemanticScholarUrlMissing() throws Exception {
        Map<String, Object> paper = Map.of(
                "paperId", "s2pid456",
                "title", "GPT-3",
                "authors", "Brown et al",
                "published", "2020",
                "summary", "Language Models are Few-Shot Learners",
                "sourceUrl", "",
                "source", "Semantic Scholar",
                "arxivId", "2005.14165",
                "pdfUrl", "https://arxiv.org/pdf/2005.14165"
        );
        when(fetcher.search(anyString(), anyInt())).thenReturn(List.of(paper));

        List<LiteratureCandidate> result = source.search("gpt-3", 5);

        assertTrue(result.get(0).sourceUrl().contains("arxiv.org/abs/2005.14165"));
        assertTrue(result.get(0).pdfUrl().contains("arxiv.org/pdf/2005.14165"));
    }
}
