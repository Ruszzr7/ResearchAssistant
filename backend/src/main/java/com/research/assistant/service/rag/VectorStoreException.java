package com.research.assistant.service.rag;

/**
 * 向量存储操作异常。
 * <p>
 * 用于将 Qdrant 等外部向量库的错误统一包装，便于 {@link VectorStoreRouter} 识别并降级到内存实现。
 */
public class VectorStoreException extends RuntimeException {

    public VectorStoreException(String message) {
        super(message);
    }

    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
