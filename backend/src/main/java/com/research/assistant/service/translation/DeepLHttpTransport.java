package com.research.assistant.service.translation;

import java.net.URI;
import java.time.Duration;

interface DeepLHttpTransport {
    Response post(URI endpoint, String authorization, String jsonBody, Duration timeout) throws Exception;

    record Response(int statusCode, String body) {
    }
}
