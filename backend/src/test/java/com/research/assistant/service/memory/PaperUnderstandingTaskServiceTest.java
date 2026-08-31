package com.research.assistant.service.memory;

import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperUnderstandingTaskServiceTest {

    private PaperMapper paperMapper;
    private PaperMemoryService paperMemoryService;
    private PaperUnderstandingService understandingService;
    private PaperAnalysisProjectionService projectionService;
    private PaperUnderstandingTaskService service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(PaperMapper.class);
        paperMemoryService = mock(PaperMemoryService.class);
        understandingService = mock(PaperUnderstandingService.class);
        projectionService = mock(PaperAnalysisProjectionService.class);
        service = new PaperUnderstandingTaskService(
                paperMapper, paperMemoryService, understandingService,
                projectionService);
    }

    @Test
    void shouldCompleteAfterSuccessfulAnalysis() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        PaperUnderstandingResult understanding = usableUnderstanding(1L);
        when(understandingService.understand(any(), any(Boolean.class), any())).thenReturn(understanding);
        when(projectionService.project(1L, understanding)).thenReturn(analysis);

        PaperAnalysis result = service.process(1L, ignored -> { });

        assertEquals(1L, result.getPaperId());
        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperMapper, times(2)).updateById(captor.capture());
        Paper completed = captor.getAllValues().stream()
                .filter(p -> ProcessingStatus.COMPLETED.equals(p.getProcessingStatus()))
                .findFirst().orElseThrow();
        assertEquals(1L, completed.getId());
        verify(paperMemoryService).ensureStructure(1L, false);
    }

    @Test
    void shouldNotIndexPaperWhenAnalysisFails() {
        when(understandingService.understand(any(), any(Boolean.class), any()))
                .thenThrow(new RuntimeException("pdf broken"));

        assertThrows(RuntimeException.class, () -> service.process(3L, ignored -> { }));
    }

    @Test
    void shouldStopBeforeLlmWhenStructuredParsingFails() {
        when(paperMemoryService.ensureStructure(4L, false))
                .thenThrow(new IllegalStateException("layout failed"));

        assertThrows(RuntimeException.class, () -> service.process(4L, ignored -> { }));

        verify(understandingService, org.mockito.Mockito.never()).understand(any(), any(Boolean.class), any());
    }

    private PaperUnderstandingResult usableUnderstanding(Long paperId) {
        return new PaperUnderstandingResult(
                10L, paperId, PaperUnderstandingService.STATUS_READY,
                1, 1, 0, 12, 5, java.util.List.of(),
                mock(com.research.assistant.service.memory.PaperGlobalProfile.class));
    }
}
