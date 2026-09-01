package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SemanticScholarFetcherTest {

    private HttpClient httpClient;
    private HttpResponse<String> response;
    private SettingsService settingsService;
    private SemanticScholarFetcher fetcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        httpClient = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        settingsService = mock(SettingsService.class);
        fetcher = new SemanticScholarFetcher(new ObjectMapper(), httpClient, settingsService);
    }

    @Test
    void searchShouldReturnNormalizedPapers() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"data":[{"paperId":"s2id1","title":"Neural Networks","abstract":"A paper.",
                "year":"2023","url":"https://example.org/1","authors":[{"name":"A B","authorId":"a1"}],
                "externalIds":{"ArXiv":"2301.00001"}}]}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> result = fetcher.search("neural", 5);

        assertEquals(1, result.size());
        assertEquals("s2id1", result.get(0).get("paperId"));
        assertEquals("A B", result.get(0).get("authors"));
        assertTrue(((List<?>) result.get(0).get("authorIds")).contains("a1"));
        assertEquals("2301.00001", result.get(0).get("arxivId"));
    }

    @Test
    void searchShouldEncodeQueryAndAddApiKey() throws Exception {
        when(settingsService.getValue("semantic_scholar_api_key")).thenReturn("secret-key");
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        fetcher.search("graph neural", 5);

        verify(httpClient).send(argThat((HttpRequest request) ->
                        request.uri().toString().contains("query=graph+neural")
                                && request.headers().firstValue("x-api-key").orElse("").equals("secret-key")),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldNotAddApiKeyWhenMissing() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        fetcher.search("neural", 5);

        verify(httpClient).send(argThat((HttpRequest request) ->
                request.headers().firstValue("x-api-key").isEmpty()), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldReturnEmptyForBlankSearch() throws Exception {
        assertTrue(fetcher.search("", 5).isEmpty());
        verify(httpClient, never()).send(any(), any());
    }
}
