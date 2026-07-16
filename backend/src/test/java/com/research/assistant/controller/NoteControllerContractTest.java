package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.service.note.NoteService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class NoteControllerContractTest {

    @Mock
    private NoteService noteService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NoteController(noteService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsNotesInsideTheStandardResultEnvelope() throws Exception {
        NoteDto note = new NoteDto();
        note.setId(9L);
        note.setTitle("Insight");
        when(noteService.listByPaper(7L)).thenReturn(List.of(note));

        mockMvc.perform(get("/api/papers/7/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].title").value("Insight"));
    }

    @Test
    void createsNotesInsideTheStandardResultEnvelope() throws Exception {
        NoteDto note = new NoteDto();
        note.setId(10L);
        note.setTitle("Saved");
        when(noteService.create(any(), any())).thenReturn(note);

        mockMvc.perform(post("/api/papers/7/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Saved\",\"content\":\"Content\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(10));
    }
}
