-- Mission 13: durable audit trail for read-only RAG consistency checks.
CREATE TABLE IF NOT EXISTS rag_consistency_audit (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id           BIGINT       NULL,
    provider           VARCHAR(32)  NOT NULL COMMENT 'MYSQL / QDRANT / MEMORY',
    status             VARCHAR(32)  NOT NULL COMMENT 'OK / MISMATCH / UNAVAILABLE / ERROR',
    active_version     INT          NULL,
    metadata_chunk_count INT         NOT NULL DEFAULT 0,
    expected_chunk_count INT         NULL,
    details_json       TEXT         NULL,
    checked_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rag_audit_paper_checked (paper_id, checked_at),
    INDEX idx_rag_audit_status_checked (status, checked_at),
    FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
