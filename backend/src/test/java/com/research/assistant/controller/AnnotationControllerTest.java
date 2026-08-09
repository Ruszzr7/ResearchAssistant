package com.research.assistant.controller;

import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.service.annotation.AnnotationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnnotationControllerTest {

    @Test
    void agentEndpointPersistsExplicitAiProvenance() throws Exception {
        AnnotationService service = mock(AnnotationService.class);
        AnnotationDto saved = new AnnotationDto();
        saved.setId(12L);
        saved.setPaperId(7L);
        saved.setType("HIGHLIGHT");
        saved.setPage(4);
        saved.setAiGenerated(true);
        when(service.create(any(Long.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(saved);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnnotationController(service)).build();

        mvc.perform(post("/api/papers/7/annotations/agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HIGHLIGHT","page":4,"color":"#f44336",
                                 "coordinates":{"coordinateSpace":"viewport","quads":[]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.aiGenerated").value(true));

        verify(service).create(any(Long.class), any(), org.mockito.ArgumentMatchers.eq(true));
    }
}
