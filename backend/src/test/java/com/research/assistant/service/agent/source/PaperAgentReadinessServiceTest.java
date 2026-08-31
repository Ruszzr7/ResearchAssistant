package com.research.assistant.service.agent.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.memory.PaperUnderstandingService;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperAgentReadinessServiceTest {

    @Test
    void keepsManualStartAvailableWhenALegacyLayoutCacheCannotBeRead() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        PaperMemoryMapper memoryMapper = mock(PaperMemoryMapper.class);
        PaperLayoutArtifactService artifactService = mock(PaperLayoutArtifactService.class);
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        when(artifactService.latestArtifact(7L)).thenThrow(new IllegalStateException("legacy cache"));

        PaperAgentReadinessView readiness = new PaperAgentReadinessService(
                paperMapper, memoryMapper, artifactService).status(7L);

        assertThat(readiness.status()).isEqualTo("NOT_STARTED");
        assertThat(readiness.conversationReady()).isFalse();
    }

    @Test
    void opensFallbackOnlyAfterBoundedAttemptsWithLocalSources() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        PaperMemoryMapper memoryMapper = mock(PaperMemoryMapper.class);
        PaperLayoutArtifactService artifactService = mock(PaperLayoutArtifactService.class);
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setPaperId(7L);
        memory.setDocumentHash("a".repeat(64));
        memory.setStructureJson("{}");
        memory.setStatus(PaperUnderstandingService.STATUS_PARTIAL);
        memory.setUnderstandingAttemptCount(2);
        when(memoryMapper.selectLatest(7L)).thenReturn(memory);
        when(artifactService.latestArtifact(7L)).thenReturn(new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser-v1", .9, Instant.now(), 1, List.of()));
        PaperAgentReadinessService service = new PaperAgentReadinessService(
                paperMapper, memoryMapper, artifactService);

        assertThat(service.status(7L).conversationReady()).isFalse();
        memory.setUnderstandingAttemptCount(3);
        PaperAgentReadinessView fallback = service.status(7L);
        assertThat(fallback.status()).isEqualTo("FALLBACK_READY");
        assertThat(fallback.conversationReady()).isTrue();
        assertThat(fallback.fallbackMode()).isTrue();
    }
}
