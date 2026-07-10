package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    private LLMService llmService;
    private ObjectMapper objectMapper;
    private LlmReranker reranker;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        objectMapper = new ObjectMapper();
        reranker = new LlmReranker(llmService, objectMapper);
    }

    @Test
    void shouldReorderByLlmScores() {
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(1L, "RAW", "first", "s1", 0.9),
                new ScoredChunk(2L, "RAW", "second", "s2", 0.8),
                new ScoredChunk(3L, "RAW", "third", "s3", 0.7)
        );
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                [{"index":1,"score":5,"reason":"ok"},
                 {"index":2,"score":9,"reason":"high"},
                 {"index":3,"score":6,"reason":"mid"}]
                """);

        List<ScoredChunk> result = reranker.rerank("query", candidates, 2);

        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).paperId());
        assertEquals(3L, result.get(1).paperId());
    }

    @Test
    void shouldFallbackToOriginalOrderOnLlmFailure() {
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(1L, "RAW", "first", "s1", 0.9),
                new ScoredChunk(2L, "RAW", "second", "s2", 0.8)
        );
        when(llmService.chat(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        List<ScoredChunk> result = reranker.rerank("query", candidates, 2);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).paperId());
        assertEquals(2L, result.get(1).paperId());
    }

    @Test
    void shouldFallbackWhenResponseIsNotValidJson() {
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(1L, "RAW", "first", "s1", 0.9)
        );
        when(llmService.chat(anyString(), anyString())).thenReturn("not json");

        List<ScoredChunk> result = reranker.rerank("query", candidates, 1);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).paperId());
    }

    @Test
    void shouldHandleEmptyCandidates() {
        List<ScoredChunk> result = reranker.rerank("query", List.of(), 5);
        assertTrue(result.isEmpty());
    }
}
