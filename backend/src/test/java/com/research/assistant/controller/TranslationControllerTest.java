package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.dto.translation.TranslationItem;
import com.research.assistant.dto.translation.TranslationResponse;
import com.research.assistant.dto.translation.TranslationStatus;
import com.research.assistant.service.translation.TranslationException;
import com.research.assistant.service.translation.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
class TranslationControllerTest {

    @Mock private TranslationService translationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TranslationController(translationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void exposesOnlyProviderReadinessAndReturnsOrderedTranslations() throws Exception {
        when(translationService.status()).thenReturn(
                new TranslationStatus("deepl", true, List.of("ZH", "EN")));
        when(translationService.translate(any())).thenReturn(new TranslationResponse(
                "deepl", "EN", "ZH",
                List.of(new TranslationItem("译文 $R_k$", "EN", false)), false));

        mockMvc.perform(get("/api/translations/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("deepl"))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.authKey").doesNotExist());

        mockMvc.perform(post("/api/translations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"texts":["The rate $R_k$"],"sourceLanguage":"EN","targetLanguage":"ZH"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].text").value("译文 $R_k$"))
                .andExpect(jsonPath("$.data.items[0].detectedSourceLanguage").value("EN"));
    }

    @Test
    void returnsSafeRecoverableProviderErrorsAndRejectsOversizedItems() throws Exception {
        when(translationService.translate(any())).thenThrow(new TranslationException(
                "PROVIDER_TEMPORARY_FAILURE", "翻译服务暂不可用，请稍后重试",
                HttpStatus.SERVICE_UNAVAILABLE, true));

        mockMvc.perform(post("/api/translations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\":[\"text\"],\"targetLanguage\":\"ZH\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("翻译服务暂不可用，请稍后重试"));

        mockMvc.perform(post("/api/translations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\":[],\"targetLanguage\":\"ZH\"}"))
                .andExpect(status().isBadRequest());
    }
}
