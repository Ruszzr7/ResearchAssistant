package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.SearchService;
import com.research.assistant.service.ReadingProgressService;
import com.research.assistant.service.metadata.MetadataEnrichmentService;
import com.research.assistant.service.ai.workflow.WorkflowService;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DynamicRequestContractTest {

    @Mock private PaperService paperService;
    @Mock private ReadingProgressService readingProgressService;
    @Mock private MetadataEnrichmentService metadataEnrichmentService;
    @Mock private WorkflowService workflowService;
    @Mock private PaperLayoutArtifactService layoutArtifactService;
    @Mock private SearchService searchService;
    @Mock private ArxivFetcher arxivFetcher;
    @Mock private AsyncTaskService asyncTaskService;

    private MockMvc paperMvc;
    private MockMvc searchMvc;
    private MockMvc workflowMvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        paperMvc = MockMvcBuilders.standaloneSetup(new PaperController(
                paperService, readingProgressService, metadataEnrichmentService, workflowService,
                layoutArtifactService))
                .setControllerAdvice(advice).build();
        searchMvc = MockMvcBuilders.standaloneSetup(new SearchController(
                searchService, paperService, arxivFetcher, asyncTaskService))
                .setControllerAdvice(advice).build();
        workflowMvc = MockMvcBuilders.standaloneSetup(new WorkflowController(workflowService))
                .setControllerAdvice(advice).build();
    }

    @Test
    void paperLayoutArtifactKeepsVersionAndBlockLocatorContract() throws Exception {
        Paper paper = new Paper();
        paper.setId(42L);
        paper.setTitle("A paper");
        when(paperService.getById(42L)).thenReturn(paper);
        when(layoutArtifactService.ensureArtifact(42L, false)).thenReturn(new PaperLayoutArtifact(
                42L,
                "a".repeat(64),
                "pdfbox-layout-v1+semantic-v1",
                0.9,
                Instant.parse("2026-07-16T00:00:00Z"),
                1,
                List.of(new DocumentBlock(
                        "p1-b0001", 1, new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04),
                        DocumentBlockRole.BODY, 0, List.of("I. INTRODUCTION"),
                        "Evidence text", null, null, 0.9))
        ));

        paperMvc.perform(get("/api/papers/42/layout-artifact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.parserVersion")
                        .value("pdfbox-layout-v1+semantic-v1"))
                .andExpect(jsonPath("$.data.blocks[0].page").value(1))
                .andExpect(jsonPath("$.data.blocks[0].role").value("BODY"));
    }

    @Test
    void paperWriteRejectsBlankTitle() throws Exception {
        paperMvc.perform(post("/api/papers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void paperWriteDoesNotAcceptServerManagedFields() throws Exception {
        when(paperService.create(any(Paper.class))).thenAnswer(invocation -> {
            Paper paper = invocation.getArgument(0);
            Paper saved = new Paper();
            saved.setId(7L);
            saved.setTitle(paper.getTitle());
            return saved;
        });

        paperMvc.perform(post("/api/papers?runWorkflow=false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"A paper\",\"id\":99,\"pdfPath\":\"../../secret\",\"tags\":[{}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paper.id").value(7));

        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperService).create(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getPdfPath()).isNull();
        assertThat(captor.getValue().getTags()).isNull();
    }

    @Test
    void readingStatusUsesDedicatedEndpoint() throws Exception {
        paperMvc.perform(post("/api/papers/7/reading-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(readingProgressService).updateStatus(7L, "READ");
    }

    @Test
    void searchExecuteRequiresKeywords() throws Exception {
        searchMvc.perform(post("/api/search/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"machine learning\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void searchExecutePreservesCompatibleFieldNames() throws Exception {
        when(searchService.executeSearch(any())).thenReturn(List.of());

        searchMvc.perform(post("/api/search/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords_en\":[\"graph neural network\"],\"paper_type\":[\"conference\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(searchService).executeSearch(captor.capture());
        assertThat(captor.getValue().get("keywords_en")).isEqualTo(List.of("graph neural network"));
    }

    @Test
    void searchImportRejectsPaperWithoutTitle() throws Exception {
        searchMvc.perform(post("/api/search/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"papers\":[{\"authors\":\"A\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void workflowConfirmKeepsSelectedAndFolderId() throws Exception {
        when(workflowService.confirm(eq("task-1"), any())).thenReturn("task-2");

        workflowMvc.perform(post("/api/research-automation/workflow/task-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selected\":[{\"title\":\"candidate\"}],\"folderId\":12,\"ignored\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-2"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowService).confirm(eq("task-1"), captor.capture());
        assertThat(captor.getValue()).containsEntry("folderId", 12L);
        assertThat(captor.getValue()).containsKey("selected");
        assertThat(captor.getValue()).doesNotContainKey("ignored");
    }
}
