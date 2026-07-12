package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.embedding.EmbeddingService;
import com.research.assistant.service.embedding.EmbeddingUnavailableException;
import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexingServiceTest {

    private final PaperAnalysisMapper analysisMapper = mock(PaperAnalysisMapper.class);
    private final DocumentChunker chunker = mock(DocumentChunker.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final PaperChunkPersistence chunkPersistence = mock(PaperChunkPersistence.class);
    private final RagIndexVersionService versionService = mock(RagIndexVersionService.class);
    private RagIndexingService service;

    @BeforeEach
    void setUp() {
        service = new RagIndexingService(analysisMapper, chunker, embeddingService, vectorStore,
                new ResearchMetrics(new SimpleMeterRegistry()), chunkPersistence, versionService);
    }

    @Test
    void shouldReturnRealIndexingResult() {
        PaperAnalysis analysis = analysis(1L);
        List<DocumentChunk> chunks = List.of(
                new DocumentChunk(1L, "METHOD", "method", "analysis"),
                new DocumentChunk(1L, "FINDING", "finding", "analysis"));
        when(analysisMapper.selectOne(any())).thenReturn(analysis);
        when(chunker.chunk(analysis)).thenReturn(chunks);
        when(embeddingService.embedBatch(List.of("method", "finding")))
                .thenReturn(List.of(List.of(1.0f, 0.0f), List.of(0.0f, 1.0f)));
        when(versionService.beginBuild(1L)).thenReturn(1);

        RagIndexingResult result = service.indexPaper(1L);

        assertThat(result.indexed()).isTrue();
        assertThat(result.chunkCount()).isEqualTo(2);
        verify(chunkPersistence).saveAll(any(), org.mockito.ArgumentMatchers.eq(1));
        verify(versionService).activate(1L, 1, 2);
        verify(vectorStore).replacePaperIndex(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1), any());
    }

    @Test
    void shouldFailWhenAnalysisIsMissing() {
        when(analysisMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.indexPaper(2L))
                .isInstanceOf(RagIndexingException.class)
                .hasMessageContaining("尚无分析结果")
                .extracting(error -> ((RagIndexingException) error).getReason())
                .isEqualTo(RagIndexingException.Reason.ANALYSIS_MISSING);
        verify(vectorStore, never()).removeByPaperId(any());
        verify(versionService, never()).beginBuild(any());
    }

    @Test
    void shouldFailWithoutDeletingOldIndexWhenEmbeddingIsUnavailable() {
        PaperAnalysis analysis = analysis(3L);
        when(analysisMapper.selectOne(any())).thenReturn(analysis);
        when(chunker.chunk(analysis)).thenReturn(List.of(
                new DocumentChunk(3L, "METHOD", "method", "analysis")));
        when(embeddingService.embedBatch(any())).thenThrow(
                new EmbeddingUnavailableException("provider unavailable", new RuntimeException("down")));

        assertThatThrownBy(() -> service.indexPaper(3L))
                .isInstanceOf(RagIndexingException.class)
                .extracting(error -> ((RagIndexingException) error).getReason())
                .isEqualTo(RagIndexingException.Reason.EMBEDDING_UNAVAILABLE);
        verify(vectorStore, never()).removeByPaperId(any());
        verify(vectorStore, never()).add(any());
    }

    @Test
    void shouldMarkVersionFailedWhenNewChunksCannotBePersisted() {
        PaperAnalysis analysis = analysis(4L);
        when(analysisMapper.selectOne(any())).thenReturn(analysis);
        when(chunker.chunk(analysis)).thenReturn(List.of(
                new DocumentChunk(4L, "METHOD", "method", "analysis")));
        when(embeddingService.embedBatch(List.of("method")))
                .thenReturn(List.of(List.of(1.0f, 0.0f)));
        when(versionService.beginBuild(4L)).thenReturn(2);
        doThrow(new RuntimeException("database unavailable"))
                .when(chunkPersistence).saveAll(any(), org.mockito.ArgumentMatchers.eq(2));

        assertThatThrownBy(() -> service.indexPaper(4L))
                .isInstanceOf(RagIndexingException.class)
                .extracting(error -> ((RagIndexingException) error).getReason())
                .isEqualTo(RagIndexingException.Reason.VECTOR_STORE_FAILED);
        verify(versionService).markFailed(org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(2), any());
        verify(vectorStore, never()).replacePaperIndex(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private PaperAnalysis analysis(Long paperId) {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(paperId);
        return analysis;
    }
}
