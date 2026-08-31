package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperMemoryQueryServiceTest {

    private final PaperMapper paperMapper = mock(PaperMapper.class);
    private final PaperMemoryMapper memoryMapper = mock(PaperMemoryMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaperMemoryQueryService service = new PaperMemoryQueryService(
            paperMapper, memoryMapper, objectMapper);

    @Test
    void shouldExposeStartForPdfWithoutMemory() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(5L)).thenReturn(paper);

        PaperMemoryStatusView view = service.status(5L);

        assertThat(view.status()).isEqualTo("NOT_STARTED");
        assertThat(view.canStart()).isTrue();
        assertThat(view.structureReady()).isFalse();
    }

    @Test
    void shouldExposePartialCoverageAndValidatedProfile() throws Exception {
        Paper paper = new Paper();
        paper.setId(6L);
        paper.setPdfPath("paper.pdf");
        PaperMemoryRecord record = new PaperMemoryRecord();
        record.setId(61L);
        record.setPaperId(6L);
        record.setStatus(PaperUnderstandingService.STATUS_PARTIAL);
        record.setStageText("部分就绪");
        record.setRevision(3);
        record.setTotalChunks(5);
        record.setCompletedChunks(4);
        record.setFailedChunks(1);
        record.setPromptTokens(100);
        record.setCompletionTokens(40);
        record.setLastErrorCode("CHUNK_SUMMARY_PARTIAL");
        record.setProfileQualityJson("{\"ready\":true}");
        record.setUpdatedAt(LocalDateTime.now());
        record.setProfileJson(objectMapper.writeValueAsString(profile()));
        when(paperMapper.selectById(6L)).thenReturn(paper);
        when(memoryMapper.selectLatest(6L)).thenReturn(record);

        PaperMemoryStatusView view = service.status(6L);

        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.canRetry()).isTrue();
        assertThat(view.profileReady()).isTrue();
        assertThat(view.profile().researchProblem()).isEqualTo("Problem");
        assertThat(view.lastErrorCode()).isEqualTo("CHUNK_SUMMARY_PARTIAL");
    }

    private PaperGlobalProfile profile() {
        return new PaperGlobalProfile(
                PaperGlobalProfile.SCHEMA_VERSION, 6L, "Paper", "AI", "Problem",
                List.of(), "OTHER", "", List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                new PaperGlobalProfile.Coverage(5, 4, 1, false), List.of(), Instant.now());
    }
}
