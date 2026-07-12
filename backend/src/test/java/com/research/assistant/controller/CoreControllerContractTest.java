package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.service.FolderService;
import com.research.assistant.service.TagService;
import com.research.assistant.service.ai.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CoreControllerContractTest {

    @Mock
    private FolderService folderService;
    @Mock
    private TagService tagService;
    @Mock
    private WorkflowService workflowService;

    private MockMvc folderMvc;
    private MockMvc tagMvc;
    private MockMvc workflowMvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        folderMvc = MockMvcBuilders.standaloneSetup(new FolderController(folderService))
                .setControllerAdvice(advice).build();
        tagMvc = MockMvcBuilders.standaloneSetup(new TagController(tagService))
                .setControllerAdvice(advice).build();
        workflowMvc = MockMvcBuilders.standaloneSetup(new WorkflowController(workflowService))
                .setControllerAdvice(advice).build();
    }

    @Test
    void folderRejectsBlankName() throws Exception {
        folderMvc.perform(post("/api/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void tagRejectsOversizedName() throws Exception {
        tagMvc.perform(post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void workflowRejectsGapRequestWithTooFewPapers() throws Exception {
        workflowMvc.perform(post("/api/agent/workflow/gap-research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperIds\":[1,2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void workflowKeepsTaskResponseShapeForValidRequest() throws Exception {
        when(workflowService.submitPaperImport(any(), any())).thenReturn("task-1");

        workflowMvc.perform(post("/api/agent/workflow/paper-import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").value("task-1"));
    }

    @Test
    void folderMoveRejectsNegativeSortOrder() throws Exception {
        folderMvc.perform(put("/api/folders/1/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
