package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexingServiceTest {

    @Test
    void persistsLocalTextChunksWithoutEmbedding() {
        PaperAnalysisMapper analysisMapper = mock(PaperAnalysisMapper.class);
        DocumentChunker chunker = mock(DocumentChunker.class);
        PaperChunkPersistence persistence = mock(PaperChunkPersistence.class);
        RagIndexVersionService versions = mock(RagIndexVersionService.class);
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(7L);
        when(analysisMapper.selectOne(any())).thenReturn(analysis);
        when(chunker.chunk(analysis)).thenReturn(List.of(
                new DocumentChunk(7L, "RAW", "paper content", "page 1")));
        when(versions.beginBuild(7L)).thenReturn(3);
        RagIndexingService service = new RagIndexingService(
                analysisMapper, chunker, new ResearchMetrics(new SimpleMeterRegistry()),
                persistence, versions);

        RagIndexingResult result = service.indexPaper(7L);

        assertThat(result.indexed()).isTrue();
        assertThat(result.chunkCount()).isEqualTo(1);
        verify(persistence).saveAll(any(), org.mockito.ArgumentMatchers.eq(3));
        verify(versions).activate(7L, 3, 1);
    }
}
