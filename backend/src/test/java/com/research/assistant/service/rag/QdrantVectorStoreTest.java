package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.service.SettingsService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantVectorStoreTest {

    private QdrantClient client;
    private PaperChunkMapper paperChunkMapper;
    private ObjectMapper objectMapper;
    private SettingsService settingsService;
    private QdrantVectorStore store;

    @BeforeEach
    void setUp() {
        client = mock(QdrantClient.class);
        paperChunkMapper = mock(PaperChunkMapper.class);
        objectMapper = new ObjectMapper();
        settingsService = mock(SettingsService.class);
        when(settingsService.getValue("qdrant_host")).thenReturn("localhost");
        when(settingsService.getValue("qdrant_port")).thenReturn("6334");
        when(settingsService.getValue("qdrant_collection")).thenReturn("paper_chunks");
        when(settingsService.getValue("qdrant_use_tls")).thenReturn("false");
        when(settingsService.getValue("qdrant_api_key")).thenReturn("");

        store = new QdrantVectorStore(settingsService, paperChunkMapper, objectMapper) {
            @Override
            protected QdrantClient createClient() {
                return client;
            }
        };
    }

    @Test
    void addShouldCreateCollectionAndPersistChunks() throws Exception {
        when(client.collectionExistsAsync(anyString(), any())).thenReturn(Futures.immediateFuture(false));
        when(client.createCollectionAsync(anyString(), any(VectorParams.class), any()))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(client.upsertAsync(anyString(), any(), any()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));

        EmbeddedChunk chunk = new EmbeddedChunk(1L, "CONTRIBUTION", "we propose x", "核心贡献", List.of(0.1f, 0.2f, 0.3f));
        store.add(List.of(chunk));

        verify(client).createCollectionAsync(eq("paper_chunks"), any(VectorParams.class), any());
        verify(client).upsertAsync(eq("paper_chunks"), any(), any());
        verify(paperChunkMapper, times(1)).insert(any(PaperChunk.class));
    }

    @Test
    void findRelevantShouldMapScoredPoints() throws Exception {
        when(client.collectionExistsAsync(anyString(), any())).thenReturn(Futures.immediateFuture(true));
        Points.ScoredPoint point = Points.ScoredPoint.newBuilder()
                .setScore(0.85f)
                .putAllPayload(Map.of(
                        "paperId", JsonWithInt.Value.newBuilder().setIntegerValue(7L).build(),
                        "chunkType", JsonWithInt.Value.newBuilder().setStringValue("METHOD").build(),
                        "content", JsonWithInt.Value.newBuilder().setStringValue("method detail").build(),
                        "source", JsonWithInt.Value.newBuilder().setStringValue("方法").build()
                ))
                .build();
        when(client.searchAsync(any(Points.SearchPoints.class), any()))
                .thenReturn(Futures.immediateFuture(List.of(point)));

        List<ScoredChunk> results = store.findRelevant(List.of(0.1f, 0.2f, 0.3f), 3, 0.7);

        assertEquals(1, results.size());
        ScoredChunk r = results.get(0);
        assertEquals(7L, r.paperId());
        assertEquals("METHOD", r.chunkType());
        assertEquals("method detail", r.content());
        assertEquals(0.85, r.score(), 0.001);
    }

    @Test
    void findRelevantShouldReturnEmptyWhenCollectionNotExists() throws Exception {
        when(client.collectionExistsAsync(anyString(), any())).thenReturn(Futures.immediateFuture(false));

        List<ScoredChunk> results = store.findRelevant(List.of(0.1f, 0.2f), 3, 0.7);

        assertTrue(results.isEmpty());
        verify(client, never()).searchAsync(any(), any());
    }

    @Test
    void removeByPaperIdShouldDeleteFromBothStores() throws Exception {
        when(client.collectionExistsAsync(anyString(), any())).thenReturn(Futures.immediateFuture(true));
        when(client.deleteAsync(anyString(), any(Points.Filter.class), any()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));

        store.removeByPaperId(5L);

        verify(paperChunkMapper).deleteByPaperId(5L);
        verify(client).deleteAsync(eq("paper_chunks"), any(Points.Filter.class), any());
    }

    @Test
    void addShouldFallbackWithoutQdrantWhenClientThrows() {
        when(client.collectionExistsAsync(anyString(), any()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("unreachable")));

        EmbeddedChunk chunk = new EmbeddedChunk(1L, "CONTRIBUTION", "we propose x", "核心贡献", List.of(0.1f, 0.2f, 0.3f));

        VectorStoreException ex = org.junit.jupiter.api.Assertions.assertThrows(
                VectorStoreException.class, () -> store.add(List.of(chunk)));
        assertTrue(ex.getMessage().contains("Qdrant collectionExists"));
        // MySQL 仍应写入
        verify(paperChunkMapper, times(1)).insert(any(PaperChunk.class));
    }
}
