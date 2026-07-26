package com.research.assistant.dto;

import java.util.Map;

/** Safe connection diagnostics. Provider response bodies and credentials are never exposed. */
public record AiConnectionTestResult(
        boolean success,
        String message,
        String provider,
        String channel,
        Map<String, String> capabilities
) {
}
