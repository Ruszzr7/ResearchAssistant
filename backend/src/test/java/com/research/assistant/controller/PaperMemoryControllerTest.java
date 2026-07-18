package com.research.assistant.controller;

import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.memory.PaperMemoryQueryService;
import com.research.assistant.service.memory.PaperMemoryStatusView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaperMemoryControllerTest {

    @Mock private PaperMemoryQueryService queryService;
    @Mock private AsyncTaskService asyncTaskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new PaperMemoryController(queryService, asyncTaskService)).build();
    }

    @Test
    void shouldReturnMemoryProgress() throws Exception {
        when(queryService.status(9L)).thenReturn(view("PARTIAL", true, true));

        mvc.perform(get("/api/papers/9/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.completedChunks").value(4))
                .andExpect(jsonPath("$.data.failedChunks").value(1));
    }

    @Test
    void shouldSubmitRecoverableUnderstandingWithClientIdempotencyKey() throws Exception {
        when(queryService.status(9L)).thenReturn(view("PARTIAL", true, true));
        when(asyncTaskService.submitProcessPaper(9L, "memory-click-1")).thenReturn("task-9");

        mvc.perform(post("/api/papers/9/memory/understand")
                        .header("Idempotency-Key", "memory-click-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-9"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void shouldNotSubmitDuplicateWhileUnderstandingIsActive() throws Exception {
        when(queryService.status(9L)).thenReturn(view("UNDERSTANDING", false, false));

        mvc.perform(post("/api/papers/9/memory/understand"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyRunning").value(true));

        verify(asyncTaskService, never()).submitProcessPaper(9L, null);
    }

    private PaperMemoryStatusView view(String status, boolean canStart, boolean canRetry) {
        return new PaperMemoryStatusView(
                9L, 91L, 3, status, "部分就绪", 100,
                5, 4, 1, 100, 40,
                true, false, canStart, canRetry, "", null, null);
    }
}
