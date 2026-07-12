package com.research.assistant.service.rag;

import com.research.assistant.service.SettingsService;
import com.research.assistant.service.embedding.EmbeddingService;
import com.research.assistant.service.embedding.EmbeddingUnavailableException;
import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    private EmbeddingService embeddingService;
    private VectorStore vectorStore;
    private SettingsService settingsService;
    private LlmReranker reranker;
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        vectorStore = mock(VectorStore.class);
        settingsService = mock(SettingsService.class);
        reranker = mock(LlmReranker.class);
        service = new RagRetrievalService(embeddingService, vectorStore, settingsService, reranker,
                new ResearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void shouldReturnSuccessForRelevantChunks() {
        ScoredChunk chunk = new ScoredChunk(1L, "METHOD", "method", "source", 0.9,
                "p1-v1-c1-hash", 1, "ANALYSIS_FIELD", null, null, null, null, null);
        when(embeddingService.embed("query")).thenReturn(List.of(1.0f));
        when(vectorStore.findRelevant(anyList(), anyInt(), anyDouble())).thenReturn(List.of(chunk));

        RagRetrievalResult result = service.retrieveWithStatus("query", 5, 0.6);

        assertEquals(RagRetrievalStatus.SUCCESS, result.status());
        assertEquals("local:1:v1:p1-v1-c1-hash", result.chunks().get(0).evidenceId());
    }

    @Test
    void shouldDistinguishEmbeddingFailureFromEmptyResults() {
        when(embeddingService.embed("query")).thenThrow(
                new EmbeddingUnavailableException("down", new RuntimeException("down")));

        RagRetrievalResult result = service.retrieveWithStatus("query", 5, 0.6);

        assertEquals(RagRetrievalStatus.EMBEDDING_UNAVAILABLE, result.status());
    }

    @Test
    void shouldExposeMemoryDegradation() {
        when(embeddingService.embed("query")).thenReturn(List.of(1.0f));
        when(vectorStore.findRelevant(anyList(), anyInt(), anyDouble())).thenReturn(List.of());
        when(vectorStore.lastOperationDegraded()).thenReturn(true);

        RagRetrievalResult result = service.retrieveWithStatus("query", 5, 0.6);

        assertEquals(RagRetrievalStatus.DEGRADED_MEMORY, result.status());
    }
}
