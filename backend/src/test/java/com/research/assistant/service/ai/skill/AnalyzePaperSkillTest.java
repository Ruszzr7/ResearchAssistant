package com.research.assistant.service.ai.skill;

import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.memory.PaperAnalysisProjectionService;
import com.research.assistant.service.memory.PaperMemoryService;
import com.research.assistant.service.memory.PaperUnderstandingResult;
import com.research.assistant.service.memory.PaperUnderstandingService;
import com.research.assistant.service.rag.RagIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyzePaperSkillTest {

    private PaperMapper paperMapper;
    private PaperMemoryService paperMemoryService;
    private PaperUnderstandingService understandingService;
    private PaperAnalysisProjectionService projectionService;
    private RagIndexingService ragIndexingService;
    private AnalyzePaperSkill skill;

    @BeforeEach
    void setUp() {
        paperMapper = mock(PaperMapper.class);
        paperMemoryService = mock(PaperMemoryService.class);
        understandingService = mock(PaperUnderstandingService.class);
        projectionService = mock(PaperAnalysisProjectionService.class);
        ragIndexingService = mock(RagIndexingService.class);
        skill = new AnalyzePaperSkill(
                paperMapper, paperMemoryService, understandingService,
                projectionService, ragIndexingService);
    }

    @Test
    void shouldIndexPaperAfterSuccessfulAnalysis() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        PaperUnderstandingResult understanding = usableUnderstanding(1L);
        when(understandingService.understand(any(), any(Boolean.class), any())).thenReturn(understanding);
        when(projectionService.project(1L, understanding)).thenReturn(analysis);

        PaperAnalysis result = skill.execute(new SkillContext("test"), 1L);

        assertEquals(1L, result.getPaperId());
        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperMapper, times(2)).updateById(captor.capture());
        Paper completed = captor.getAllValues().stream()
                .filter(p -> ProcessingStatus.COMPLETED.equals(p.getProcessingStatus()))
                .findFirst().orElseThrow();
        assertEquals(1L, completed.getId());
        verify(paperMemoryService).ensureStructure(1L, false);
        verify(ragIndexingService).indexPaper(1L);
    }

    @Test
    void shouldNotFailWhenRagIndexingFails() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(2L);
        PaperUnderstandingResult understanding = usableUnderstanding(2L);
        when(understandingService.understand(any(), any(Boolean.class), any())).thenReturn(understanding);
        when(projectionService.project(2L, understanding)).thenReturn(analysis);
        doThrow(new RuntimeException("qdrant down")).when(ragIndexingService).indexPaper(2L);

        PaperAnalysis result = skill.execute(new SkillContext("test"), 2L);

        assertEquals(2L, result.getPaperId());
        verify(ragIndexingService).indexPaper(2L);
    }

    @Test
    void shouldNotIndexPaperWhenAnalysisFails() {
        when(understandingService.understand(any(), any(Boolean.class), any()))
                .thenThrow(new RuntimeException("pdf broken"));

        assertThrows(RuntimeException.class, () -> skill.execute(new SkillContext("test"), 3L));
        verify(ragIndexingService, never()).indexPaper(any());
    }

    @Test
    void shouldStopBeforeLlmWhenStructuredParsingFails() {
        when(paperMemoryService.ensureStructure(4L, false))
                .thenThrow(new IllegalStateException("layout failed"));

        assertThrows(RuntimeException.class, () -> skill.execute(new SkillContext("test"), 4L));

        verify(understandingService, never()).understand(any(), any(Boolean.class), any());
        verify(ragIndexingService, never()).indexPaper(any());
    }

    private PaperUnderstandingResult usableUnderstanding(Long paperId) {
        return new PaperUnderstandingResult(
                10L, paperId, PaperUnderstandingService.STATUS_READY,
                1, 1, 0, 12, 5, java.util.List.of(), null);
    }
}
