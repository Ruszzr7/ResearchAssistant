package com.research.assistant.service.embedding;

/**
 * Embedding 服务不可用时抛出的异常。
 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
