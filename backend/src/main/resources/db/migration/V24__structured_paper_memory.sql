-- Redesign phase 5: versioned structured paper memory built from layout facts.
CREATE TABLE paper_memory (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id               BIGINT       NOT NULL,
    document_hash          CHAR(64)     NOT NULL,
    layout_parser_version  VARCHAR(128) NOT NULL,
    schema_version         VARCHAR(48)  NOT NULL,
    status                 VARCHAR(24)  NOT NULL DEFAULT 'STRUCTURED',
    structure_json         MEDIUMTEXT   NOT NULL,
    chunk_summaries_json   MEDIUMTEXT   NULL,
    profile_json           MEDIUMTEXT   NULL,
    memory_quality_json    TEXT         NULL,
    revision               INT          NOT NULL DEFAULT 1,
    last_error_code        VARCHAR(64)  NULL,
    generated_at           DATETIME(6)  NOT NULL,
    created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_memory_source_version (
        paper_id, document_hash, layout_parser_version, schema_version
    ),
    INDEX idx_memory_paper_updated (paper_id, updated_at),
    INDEX idx_memory_status_updated (status, updated_at),
    CONSTRAINT fk_paper_memory_paper FOREIGN KEY (paper_id) REFERENCES paper(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
