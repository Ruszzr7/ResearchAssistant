package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link InMemoryVectorStore} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class InMemoryVectorStoreTest {

    @Mock
    private PaperChunkMapper paperChunkMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore(paperChunkMapper, objectMapper);
    }

    @Test
    void shouldAddAndFindRelevantChunks() {
        List<Float> vector = List.of(1.0f, 0.0f, 0.0f);
        vectorStore.add(List.of(new EmbeddedChunk(1L, "RAW", "hello", "source", vector)));

        List<ScoredChunk> result = vectorStore.findRelevant(vector, 5, 0.5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).paperId()).isEqualTo(1L);
        assertThat(result.get(0).score()).isEqualTo(1.0);
        verify(paperChunkMapper).insert(any(PaperChunk.class));
    }

    @Test
    void shouldFilterByMinScore() {
        List<Float> v1 = List.of(1.0f, 0.0f, 0.0f);
        List<Float> v2 = List.of(0.0f, 1.0f, 0.0f);
        vectorStore.add(List.of(
                new EmbeddedChunk(1L, "RAW", "a", "s1", v1),
                new EmbeddedChunk(2L, "RAW", "b", "s2", v2)));

        List<ScoredChunk> result = vectorStore.findRelevant(v1, 5, 0.99);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).paperId()).isEqualTo(1L);
    }

    @Test
    void shouldRemoveByPaperId() {
        List<Float> vector = List.of(1.0f, 0.0f, 0.0f);
        vectorStore.add(List.of(new EmbeddedChunk(1L, "RAW", "hello", "source", vector)));
        vectorStore.removeByPaperId(1L);

        List<ScoredChunk> result = vectorStore.findRelevant(vector, 5, 0.0);
        assertThat(result).isEmpty();
        verify(paperChunkMapper).deleteByPaperId(1L);
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        assertThat(vectorStore.findRelevant(List.of(), 5, 0.0)).isEmpty();
        verify(paperChunkMapper, never()).insert(any(PaperChunk.class));
    }
}
