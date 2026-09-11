package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.dto.research.ResearchSessionPage;
import com.research.assistant.dto.research.ResearchSessionSummary;
import com.research.assistant.service.research.ResearchSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ResearchSessionControllerTest {

    @Mock private ResearchSessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResearchSessionController(sessionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsArchivedAgentConversations() throws Exception {
        ResearchSessionSummary summary = new ResearchSessionSummary();
        summary.setId(8L);
        summary.setTitle("RSMA 精读");
        when(sessionService.page(true, "RSMA", 2, 12))
                .thenReturn(new ResearchSessionPage(List.of(summary), 25, 2, 12, 3));

        mockMvc.perform(get("/api/research/sessions")
                        .param("archived", "true")
                        .param("keyword", "RSMA")
                        .param("page", "2")
                        .param("size", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(8))
                .andExpect(jsonPath("$.data.records[0].title").value("RSMA 精读"))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.pages").value(3));

    }

    @Test
    void validatesCreatePayload() throws Exception {
        mockMvc.perform(post("/api/research/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperIds\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/research/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperIds\":[1],\"lastPage\":1}"))
                .andExpect(status().isOk());

        verify(sessionService).create(any());
    }
}
