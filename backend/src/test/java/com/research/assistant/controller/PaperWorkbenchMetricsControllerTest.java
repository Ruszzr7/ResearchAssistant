package com.research.assistant.controller;

import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import com.research.assistant.dto.workbench.PdfWorkbenchMetricsSnapshot;
import com.research.assistant.service.workbench.PdfWorkbenchEvalService;
import com.research.assistant.service.workbench.PdfWorkbenchMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaperWorkbenchMetricsControllerTest {

    @Mock private PdfWorkbenchMetricsService metricsService;
    @Mock private PdfWorkbenchEvalService evalService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PaperWorkbenchMetricsController(metricsService, evalService)).build();
    }

    @Test
    void returnsStableAggregateMetricShape() throws Exception {
        PdfWorkbenchEvalMetrics evaluation = evaluation();
        PdfWorkbenchMetricsSnapshot snapshot = new PdfWorkbenchMetricsSnapshot(
                Instant.EPOCH, 30, false,
                new PdfWorkbenchMetricsSnapshot.LayoutSummary(
                        2, 0.9, 0, 0, 0, 0, 0, 100, 2, 3, 0, Map.of("pdfbox", 2)),
                new PdfWorkbenchMetricsSnapshot.AnchorSummary(1, 1, 0, 0, 0, 0.9, 1, 0, 0),
                new PdfWorkbenchMetricsSnapshot.EvidenceSummary(1, 2, 2, 1, 0, 0, 0, 0, 0, 0, 0),
                new PdfWorkbenchMetricsSnapshot.RunSummary(1, 1, 0, 0, 0, 1, 0, 100, 20, 2, 0),
                List.of(), evaluation);
        when(metricsService.snapshot(30)).thenReturn(snapshot);

        mockMvc.perform(get("/api/workbench/metrics").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.layout.latestArtifacts").value(2))
                .andExpect(jsonPath("$.data.runs.completionRate").value(1.0))
                .andExpect(jsonPath("$.data.evaluation.deterministicPassed").value(7));
    }

    @Test
    void exposesDetailedRepeatableEvaluation() throws Exception {
        when(evalService.evaluate()).thenReturn(evaluation());

        mockMvc.perform(get("/api/workbench/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deterministicCases").value(7))
                .andExpect(jsonPath("$.data.passRate").value(1.0));
    }

    private PdfWorkbenchEvalMetrics evaluation() {
        return new PdfWorkbenchEvalMetrics(
                1, Instant.EPOCH, 7, 7, 4, 0, 0, 4, 1, List.of());
    }
}
