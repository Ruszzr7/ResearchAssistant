package com.research.assistant.service.agent.core;

import java.util.Arrays;

/** One bounded application-generated crop attached to the next Agent model request. */
public record AgentVisualContent(String sourceObjectId, int pageNumber, String contentType,
                                 String mimeType, byte[] bytes, int width, int height) {
    public AgentVisualContent {
        if (sourceObjectId == null || sourceObjectId.isBlank()) {
            throw new IllegalArgumentException("visual sourceObjectId is required");
        }
        if (pageNumber <= 0) throw new IllegalArgumentException("visual page must be positive");
        contentType = contentType == null ? "TEXT" : contentType;
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("visual mimeType must be an image");
        }
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("visual bytes are required");
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
