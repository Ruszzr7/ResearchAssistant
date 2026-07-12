package com.research.assistant.service.rag;

/** RAG 索引版本的持久化状态。 */
public enum RagIndexStatus {
    BUILDING,
    READY,
    ACTIVE,
    RETIRED,
    FAILED
}
