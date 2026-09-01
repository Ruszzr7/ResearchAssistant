package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.dto.AiConnectionTestResult;
import com.research.assistant.dto.AiModelListResult;
import com.research.assistant.entity.Settings;
import com.research.assistant.service.AiModelCatalogService;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.dto.agent.AiCapabilityView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsControllerContractTest {

    @Mock
    private SettingsService settingsService;
    @Mock
    private AiModelCatalogService modelCatalogService;
    @Mock
    private AiCapabilityService capabilityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettingsController(settingsService, modelCatalogService, capabilityService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSettingsMasksEverySensitiveKey() throws Exception {
        Settings main = new Settings("api_key", "sk-main-secret");
        Settings legacy = new Settings("apiKey", "legacy-secret");
        Settings ieee = new Settings("ieee_xplore_api_key", "ieee-secret");
        Settings semanticScholar = new Settings("semantic_scholar_api_key", "semantic-secret");
        when(settingsService.getAll()).thenReturn(List.of(main, legacy, ieee, semanticScholar));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").value("sk-mai****cret"))
                .andExpect(jsonPath("$.data[1].value").value("legacy****cret"))
                .andExpect(jsonPath("$.data[2].value").value("ieee-s****cret"))
                .andExpect(jsonPath("$.data[3].value").value("semant****cret"))
                .andExpect(jsonPath("$.data[0].value", not(containsString("secret"))));
    }

    @Test
    void unknownSettingReturnsSafeBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("不支持的设置项: injected_key"))
                .when(settingsService).saveAll(anyList());

        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"keyName\":\"injected_key\",\"value\":\"value\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    void connectionFailureDoesNotExposeProviderMessage() throws Exception {
        when(settingsService.testConnection()).thenThrow(new RuntimeException("Authorization sk-secret at https://provider"));

        mockMvc.perform(post("/api/settings/test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(containsString("上游模型服务")))
                .andExpect(jsonPath("$.message").value(not(containsString("sk-secret"))));
    }

    @Test
    void connectionSuccessReturnsProviderCapabilities() throws Exception {
        when(settingsService.testConnection()).thenReturn(new AiConnectionTestResult(
                true, "连接成功", "kimi", "coding",
                Map.of("chat", "已验证", "stream", "兼容 SSE")));

        mockMvc.perform(post("/api/settings/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("kimi"))
                .andExpect(jsonPath("$.data.channel").value("coding"))
                .andExpect(jsonPath("$.data.capabilities.chat").value("已验证"));
    }

    @Test
    void modelCatalogReturnsOnlyModelIdentifiers() throws Exception {
        when(modelCatalogService.list(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiModelListResult(List.of("kimi-k2.6", "kimi-k3")));

        mockMvc.perform(post("/api/settings/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"sk-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.models[0]").value("kimi-k2.6"))
                .andExpect(jsonPath("$.data.models[1]").value("kimi-k3"))
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    void capabilityProbeUsesDraftAndDoesNotSaveSettings() throws Exception {
        when(capabilityService.probeDraft(org.mockito.ArgumentMatchers.eq(com.research.assistant.service.agent.capability.AiModelRole.CHAT),
                org.mockito.ArgumentMatchers.eq("https://draft.example/v1"),
                org.mockito.ArgumentMatchers.eq("draft-model"),
                org.mockito.ArgumentMatchers.eq("draft-key")))
                .thenReturn(new AiCapabilityView("CHAT", "VERIFIED", true, true, true, true,
                        false, false, null, null, null, null));

        mockMvc.perform(post("/api/settings/capabilities/CHAT/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://draft.example/v1\",\"model\":\"draft-model\",\"apiKey\":\"draft-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));

        verify(capabilityService).probeDraft(org.mockito.ArgumentMatchers.eq(com.research.assistant.service.agent.capability.AiModelRole.CHAT),
                org.mockito.ArgumentMatchers.eq("https://draft.example/v1"),
                org.mockito.ArgumentMatchers.eq("draft-model"),
                org.mockito.ArgumentMatchers.eq("draft-key"));
        verify(settingsService, never()).saveAll(any());
    }
}
