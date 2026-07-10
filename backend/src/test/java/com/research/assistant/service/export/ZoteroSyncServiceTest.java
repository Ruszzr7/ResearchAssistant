package com.research.assistant.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ZoteroSyncServiceTest {

    private final SettingsService settingsService = mock(SettingsService.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private final ZoteroSyncService service = new ZoteroSyncService(settingsService, new ObjectMapper(), httpClient);

    @Test
    void shouldThrowWhenNotConfigured() {
        when(settingsService.getValue("zotero_user_id")).thenReturn("");
        assertThrows(IllegalArgumentException.class, () -> service.sync(List.of(new Paper())));
    }

    @Test
    void shouldSendItemsToZotero() throws Exception {
        when(settingsService.getValue("zotero_user_id")).thenReturn("12345");
        when(settingsService.getValue("zotero_api_key")).thenReturn("key");
        when(settingsService.getValue("zotero_collection_key")).thenReturn("COL");

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Paper paper = new Paper();
        paper.setTitle("Zotero Test");
        paper.setAuthors("[{\"name\":\"A B\"}]");
        paper.setYear(2024);

        int count = service.sync(List.of(paper));

        assertThat(count).isEqualTo(1);
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
