package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    private final PaperChunkMapper mapper = mock(PaperChunkMapper.class);
    private final SettingsService settings = mock(SettingsService.class);
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(mapper, settings,
                new ResearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void retrievesActiveChunksWithLocalLexicalRanking() {
        when(mapper.selectAllActive()).thenReturn(List.of(
                chunk(7L, "sinr", "The SINR is defined for the common stream."),
                chunk(8L, "latency", "The experiment reports end-to-end latency.")));

        RagRetrievalResult result = service.retrieveWithStatus("SINR common stream", 5, 0.5);

        assertThat(result.status()).isEqualTo(RagRetrievalStatus.SUCCESS);
        assertThat(result.chunks()).singleElement().satisfies(item -> {
            assertThat(item.paperId()).isEqualTo(7L);
            assertThat(item.content()).contains("SINR");
        });
    }

    @Test
    void doesNotInvokeAnyModelAndReturnsEmptyWhenThereIsNoLexicalMatch() {
        when(mapper.selectAllActive()).thenReturn(List.of(
                chunk(7L, "method", "Alternating optimization is used.")));

        assertThat(service.retrieveWithStatus("unrelated dataset", 5, 0.5).status())
                .isEqualTo(RagRetrievalStatus.EMPTY);
    }

    private PaperChunk chunk(long paperId, String key, String content) {
        PaperChunk value = new PaperChunk();
        value.setPaperId(paperId);
        value.setIndexVersion(1);
        value.setChunkKey(key);
        value.setChunkType("RAW");
        value.setSourceType("PDF_TEXT");
        value.setContent(content);
        value.setSource("paper " + paperId);
        value.setPageStart(1);
        return value;
    }
}
