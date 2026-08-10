package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AiModelListRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelCatalogServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void queriesTheCompatibleModelsEndpointAndUsesTheSavedKeyForAMaskedValue() throws Exception {
        SettingsService settings = mock(SettingsService.class);
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(settings.getValue("api_key")).thenReturn("sk-real-secret");
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"data":[{"id":"model-z"},{"id":"model-a"},{"id":"model-a"}]}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiModelCatalogService service = new AiModelCatalogService(
                new ObjectMapper(), settings, client);

        List<String> models = service.list(new AiModelListRequest(
                "https://api.example.com/v1/chat/completions", "sk-old****1234")).models();

        assertThat(models).containsExactly("model-a", "model-z");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.example.com/v1/models");
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .contains("Bearer sk-real-secret");
    }
}
