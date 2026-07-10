package com.research.assistant.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorStoreRouterTest {

    @Test
    void shouldRouteToMemoryWhenQdrantDisabled() {
        InMemoryVectorStore memory = mock(InMemoryVectorStore.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        VectorStoreRouter router = new VectorStoreRouter(memory, qdrant, false);

        List<ScoredChunk> expected = List.of(new ScoredChunk(1L, "CONTRIBUTION", "c", "s", 0.8));
        when(memory.findRelevant(anyList(), anyInt(), anyDouble())).thenReturn(expected);

        List<ScoredChunk> result = router.findRelevant(List.of(0.1f), 3, 0.7);

        assertSame(expected, result);
        verify(qdrant, never()).findRelevant(anyList(), anyInt(), anyDouble());
    }

    @Test
    void shouldRouteToQdrantWhenEnabled() {
        InMemoryVectorStore memory = mock(InMemoryVectorStore.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        VectorStoreRouter router = new VectorStoreRouter(memory, qdrant, true);

        List<EmbeddedChunk> chunks = List.of(new EmbeddedChunk(1L, "RAW", "x", "src", List.of(0.1f)));
        router.add(chunks);

        verify(qdrant).add(chunks);
        verify(memory, never()).add(anyList());
    }

    @Test
    void shouldFallbackToMemoryWhenQdrantFails() {
        InMemoryVectorStore memory = mock(InMemoryVectorStore.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        VectorStoreRouter router = new VectorStoreRouter(memory, qdrant, true);

        List<ScoredChunk> expected = List.of(new ScoredChunk(1L, "CONTRIBUTION", "c", "s", 0.8));
        when(qdrant.findRelevant(anyList(), anyInt(), anyDouble())).thenThrow(new VectorStoreException("qdrant down"));
        when(memory.findRelevant(anyList(), anyInt(), anyDouble())).thenReturn(expected);

        List<ScoredChunk> result = router.findRelevant(List.of(0.1f), 3, 0.7);

        assertSame(expected, result);
        verify(memory).findRelevant(anyList(), anyInt(), anyDouble());
    }

    @Test
    void shouldCleanupMemoryWhenQdrantRemoveFails() {
        InMemoryVectorStore memory = mock(InMemoryVectorStore.class);
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        VectorStoreRouter router = new VectorStoreRouter(memory, qdrant, true);

        doThrow(new VectorStoreException("qdrant down")).when(qdrant).removeByPaperId(1L);

        router.removeByPaperId(1L);

        verify(memory).removeByPaperId(1L);
    }
}
