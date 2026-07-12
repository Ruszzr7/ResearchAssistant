package com.research.assistant.service.rag;

/** RAG 召回状态；用于区分无命中与系统降级。 */
public enum RagRetrievalStatus {
    DISABLED,
    EMPTY,
    SUCCESS,
    DEGRADED_MEMORY,
    EMBEDDING_UNAVAILABLE,
    VECTOR_STORE_UNAVAILABLE
}
