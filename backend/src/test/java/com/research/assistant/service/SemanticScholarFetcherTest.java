package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticScholarFetcherTest {

    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private HttpResponse<String> response;
    private SettingsService settingsService;
    private SemanticScholarFetcher fetcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        settingsService = mock(SettingsService.class);
        fetcher = new SemanticScholarFetcher(objectMapper, httpClient, settingsService);
    }

    @Test
    void searchShouldReturnNormalizedPapers() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "data": [{
                    "paperId": "s2id1",
                    "title": "Neural Networks",
                    "abstract": "A paper.",
                    "year": "2023",
                    "url": "https://example.org/1",
                    "authors": [{"name": "A B", "authorId": "a1"}],
                    "externalIds": {"ArXiv": "2301.00001"}
                  }]
                }
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
    void fetchCitationsShouldUnwrapCitingPaper() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "data": [{
                    "citingPaper": {
                      "paperId": "s2id2",
                      "title": "Citing Paper",
                      "abstract": " cites.",
                      "year": "2024",
                      "url": "https://example.org/2",
                      "authors": [{"name": "C D", "authorId": "a2"}]
                    }
                  }]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> result = fetcher.fetchCitations("s2id1", 5);

        assertEquals(1, result.size());
        assertEquals("s2id2", result.get(0).get("paperId"));
        assertEquals("Citing Paper", result.get(0).get("title"));
    }

    @Test
    void fetchReferencesShouldUnwrapCitedPaper() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "data": [{
                    "citedPaper": {
                      "paperId": "s2id3",
                      "title": "Cited Paper",
                      "abstract": "refs.",
                      "year": "2020",
                      "url": "https://example.org/3",
                      "authors": [{"name": "E F"}]
                    }
                  }]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> result = fetcher.fetchReferences("s2id1", 5);

        assertEquals(1, result.size());
        assertEquals("s2id3", result.get(0).get("paperId"));
    }

    @Test
    void fetchAuthorPapersShouldReturnPapers() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "data": [{
                    "paperId": "s2id4",
                    "title": "Author Paper",
                    "abstract": "by author.",
                    "year": "2022",
                    "url": "https://example.org/4",
                    "authors": [{"name": "G H"}]
                  }]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> result = fetcher.fetchAuthorPapers("a1", 5);

        assertEquals(1, result.size());
        assertEquals("s2id4", result.get(0).get("paperId"));
    }

    @Test
    void fetchPaperAuthorIdsShouldExtractIds() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {
                  "authors": [{"name": "A B", "authorId": "a1"}, {"name": "C D", "authorId": "a2"}]
                }
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<String> ids = fetcher.fetchPaperAuthorIds("s2id1");

        assertEquals(List.of("a1", "a2"), ids);
    }

    @Test
    void shouldReturnEmptyOnNon200Response() throws Exception {
        when(response.statusCode()).thenReturn(429);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        List<Map<String, Object>> result = fetcher.fetchCitations("s2id1", 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldEncodePaperIdInUrl() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        fetcher.fetchCitations("s2/id with space", 5);

        verify(httpClient).send(org.mockito.ArgumentMatchers.argThat((HttpRequest req) ->
                req.uri().toString().contains("s2%2Fid+with+space")), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldAddApiKeyWhenConfigured() throws Exception {
        when(settingsService.getValue("semantic_scholar_api_key")).thenReturn("secret-key");
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        fetcher.fetchCitations("s2id1", 5);

        verify(httpClient).send(org.mockito.ArgumentMatchers.argThat((HttpRequest req) ->
                req.headers().firstValue("x-api-key").orElse("").equals("secret-key")), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldNotAddApiKeyWhenMissing() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        fetcher.fetchCitations("s2id1", 5);

        verify(httpClient).send(org.mockito.ArgumentMatchers.argThat((HttpRequest req) ->
                req.headers().firstValue("x-api-key").isEmpty()), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldReturnEmptyForBlankSearch() throws Exception {
        List<Map<String, Object>> result = fetcher.search("", 5);
        assertTrue(result.isEmpty());
        verify(httpClient, never()).send(any(), any());
    }
}
