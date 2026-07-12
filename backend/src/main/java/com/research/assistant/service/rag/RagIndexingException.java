package com.research.assistant.service.rag;

/** RAG 索引失败；reason 用于稳定的指标标签，message 用于任务错误展示。 */
public class RagIndexingException extends RuntimeException {

    public enum Reason {
        INVALID_PAPER,
        ANALYSIS_MISSING,
        NO_CHUNKS,
        EMBEDDING_UNAVAILABLE,
        EMBEDDING_MISMATCH,
        VECTOR_STORE_FAILED,
        INDEX_VERSION_FAILED
    }

    private final Reason reason;

    public RagIndexingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RagIndexingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
