package com.research.assistant.controller;

import com.research.assistant.service.PaperStorageDirectoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaperStorageControllerTest {

    @Test
    void opensOnlyConfiguredPaperDirectory() throws Exception {
        PaperStorageDirectoryService service = mock(PaperStorageDirectoryService.class);
        when(service.openDirectory()).thenReturn(Path.of("C:/ResearchAssistant/data/papers"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new PaperStorageController(service))
                .build();

        mvc.perform(post("/api/papers/storage-directory/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path")
                        .value("C:\\ResearchAssistant\\data\\papers"));
    }
}
